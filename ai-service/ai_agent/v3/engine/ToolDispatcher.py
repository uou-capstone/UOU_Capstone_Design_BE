# -*- coding: utf-8 -*-
"""
ToolDispatcher

Design doc section 6: Orchestration sub-agent connector.
Converts OrchestratorPlan actions into actual agent calls.
On tool failure, degrades to a SYSTEM message (soft-failure).

Tool execution mapping:
  EXPLAIN_PAGE           -> ExplainerAgent
  ANSWER_QUESTION        -> QaAgent
  GENERATE_QUIZ_*        -> QuizAgents
  AUTO_GRADE_MCQ_OX      -> internal logic (GraderAgent._grade_auto)
  GRADE_SHORT_OR_ESSAY   -> GraderAgent._grade_llm
  REPAIR_MISCONCEPTION   -> MisconceptionRepairAgent
  WRITE_FEEDBACK_ENTRY   -> internal logic
"""
from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Any, AsyncGenerator, Dict, List, Optional

from ai_agent.v3.agents.ExplainerAgent import ExplainerAgent
from ai_agent.v3.agents.GraderAgent import GraderAgent
from ai_agent.v3.agents.MisconceptionRepairAgent import MisconceptionRepairAgent
from ai_agent.v3.agents.QaAgent import QaAgent
from ai_agent.v3.agents.QuizAgents import QuizAgents
from ai_agent.v3.engine.LearningContextCollector import LearningContextCollector
from ai_agent.v3.engine.PlanVerifier import PlanVerifier
from ai_agent.v3.engine.QaThreadService import qa_thread_service
from ai_agent.v3.engine.QuizDiagnosisService import quiz_diagnosis_service
from ai_agent.bridge.GeminiBridgeClient import GeminiBridgeClient
from ai_agent.types.domain import (
    ActionType,
    NdjsonEvent,
    NdjsonEventType,
    OrchestratorAction,
    OrchestratorPlan,
    PageStatus,
    QuizRecord,
    SessionState,
    ToolName,
)
from ai_agent.v3.exam_type_aliases import normalize_exam_type_string
from app.services.error_mapping import stable_error_type

_MSG_NO_QUIZ_RECORD = "채점할 퀴즈 기록을 찾지 못했습니다."
_MSG_MISSING_QUIZ_CONTEXT = "퀴즈 생성을 위해 현재 페이지 자료가 필요합니다. 먼저 PDF 설명을 진행한 뒤 다시 시도해 주세요."
_MSG_REPAIR_FAILED = "오답 교정 설명을 안정적으로 생성하지 못했습니다. 잠시 후 다시 시도해 주세요."
_MSG_TOOL_FAILED = "요청한 학습 작업을 처리하는 중 오류가 발생했습니다."


class ToolDispatcher:
    """
    Receives OrchestratorPlan, executes each action, and streams NdjsonEvents.
    Tool failures are handled as degraded SYSTEM messages to keep the flow alive.
    """

    def __init__(self, bridge: GeminiBridgeClient):
        self._bridge = bridge
        self._explainer = ExplainerAgent(bridge)
        self._qa = QaAgent(bridge)
        self._quiz = QuizAgents(bridge)
        self._grader = GraderAgent(bridge)
        self._repair = MisconceptionRepairAgent(bridge)
        self._context_collector = LearningContextCollector()
        self._plan_verifier = PlanVerifier()

    async def dispatch(
        self,
        plan: OrchestratorPlan,
        state: SessionState,
        event_payload: Dict[str, Any],
        event_type: str | None = None,
    ) -> AsyncGenerator[NdjsonEvent, None]:
        """
        Executes plan.actions in order and streams NdjsonEvents.

        Args:
            plan: Execution plan produced by Orchestrator
            state: Current session state (modified in-place)
            event_payload: Original AppEvent.payload (extra params for agent calls)
        """
        verification = self._plan_verifier.verify(
            plan,
            state,
            event_type=event_type,
            event_payload=event_payload,
        )
        for action in verification.plan.actions:
            async for event in self._execute_action(action, state, event_payload):
                yield event

    async def _execute_action(
        self,
        action: OrchestratorAction,
        state: SessionState,
        event_payload: Dict[str, Any],
    ) -> AsyncGenerator[NdjsonEvent, None]:
        try:
            if action.type == ActionType.SEND_MESSAGE:
                yield NdjsonEvent(
                    type=NdjsonEventType.AGENT_DELTA,
                    agent="system",
                    channel="main",
                    delta=action.message or "",
                )
                if action.ui_state:
                    yield NdjsonEvent(
                        type=NdjsonEventType.DONE,
                        agent="system",
                        final=True,
                        data={"ui": action.ui_state},
                    )

            elif action.type == ActionType.SET_UI_STATE:
                yield NdjsonEvent(
                    type=NdjsonEventType.DONE,
                    agent="system",
                    final=True,
                    data={"ui": action.ui_state or {}},
                )

            elif action.type == ActionType.CALL_TOOL:
                async for event in self._execute_tool(action, state, event_payload):
                    yield event

        except Exception as exc:
            yield NdjsonEvent(
                type=NdjsonEventType.ERROR,
                agent="system",
                tool=action.tool.value if action.tool else None,
                code="TOOL_EXECUTION_FAILED",
                message=_MSG_TOOL_FAILED,
                details=[{"errorType": stable_error_type(exc)}],
            )

    async def _execute_tool(
        self,
        action: OrchestratorAction,
        state: SessionState,
        event_payload: Dict[str, Any],
    ) -> AsyncGenerator[NdjsonEvent, None]:
        tool = action.tool
        # Only merge explicitly allowed keys from event_payload.
        # Prevents clients from injecting pdf_path, detail, etc. to override action.params.
        _PAYLOAD_ALLOWED_KEYS = {
            "question",
            "text",
            "answers",
            "quiz_type",
            "quizType",
            "exam_type",
            "examType",
            "selectedType",
            "accept",
            "decision",
            "page_number",
        }
        safe_payload = {k: v for k, v in event_payload.items() if k in _PAYLOAD_ALLOWED_KEYS}
        params = {**action.params, **safe_payload}
        page_state = state.get_current_page_state()

        # ----------------------------------------------------------------
        # EXPLAIN_PAGE
        # ----------------------------------------------------------------
        if tool == ToolName.EXPLAIN_PAGE:
            page_state.status = PageStatus.EXPLAINING
            detail = params.get("detail", "NORMAL")
            pdf_path = page_state.pdf_path or state.pdf_path or ""
            chapter_title = page_state.chapter_title  # None -> ExplainerAgent uses "page N"
            learning_context = self._context_collector.collect(state, page_state)

            # Collect explanation stream (cap at 2000 chars for Redis storage)
            answer_chunks: List[str] = []
            async for event in self._explainer.run_stream(
                learning_context.page_number,
                pdf_path,
                chapter_title,
                detail,
                page_text=learning_context.page_text,
                prev_text=learning_context.prev_text,
                next_text=learning_context.next_text,
                learner_level=state.learner.proficiency_level,
                learner_memory_digest=learning_context.learner_memory_digest,
            ):
                if event.type == NdjsonEventType.AGENT_DELTA and event.channel == "main" and event.delta:
                    answer_chunks.append(event.delta)
                if event.type == NdjsonEventType.DONE:
                    continue
                yield event

            full_text = "".join(answer_chunks)
            page_state.explanation = full_text[:2000] if len(full_text) > 2000 else full_text
            page_state.status = PageStatus.EXPLAINED
            state.append_message("assistant", page_state.explanation)
            next_widget = params.get("next_widget", "NEXT_PAGE_DECISION")
            if next_widget:
                yield NdjsonEvent(
                    type=NdjsonEventType.DONE,
                    agent="system",
                    final=True,
                    data={"ui": {"widget": next_widget}},
                )

        # ----------------------------------------------------------------
        # ANSWER_QUESTION
        # ----------------------------------------------------------------
        elif tool == ToolName.ANSWER_QUESTION:
            question = params.get("question") or params.get("text") or ""
            pdf_path = page_state.pdf_path or state.pdf_path or ""
            chapter_title = page_state.chapter_title
            learning_context = self._context_collector.collect(state, page_state)

            answer_chunks: List[str] = []
            async for event in self._qa.run_stream(
                question,
                pdf_path,
                chapter_title,
                page_number=learning_context.page_number,
                page_text=learning_context.page_text,
                prev_text=learning_context.prev_text,
                next_text=learning_context.next_text,
                learner_memory_digest=learning_context.learner_memory_digest,
                qa_thread_digest=learning_context.qa_thread_digest,
            ):
                if event.type == NdjsonEventType.AGENT_DELTA and event.channel == "main" and event.delta:
                    answer_chunks.append(event.delta)
                if event.type == NdjsonEventType.DONE:
                    continue
                yield event
            full_text = "".join(answer_chunks)
            if full_text:
                qa_thread_service.append_turn(
                    state,
                    page_number=learning_context.page_number,
                    question=question,
                    answer=full_text,
                    metadata={"agent": "qa"},
                )
                state.append_message("assistant", full_text[:2000], {"agent": "qa"})
            yield NdjsonEvent(
                type=NdjsonEventType.DONE,
                agent="system",
                final=True,
                data={"ui": {"widget": "NEXT_PAGE_DECISION"}},
            )

        # ----------------------------------------------------------------
        # GENERATE_QUIZ_*
        # ----------------------------------------------------------------
        elif tool in (
            ToolName.GENERATE_QUIZ_FIVE_CHOICE,
            ToolName.GENERATE_QUIZ_OX,
            ToolName.GENERATE_QUIZ_SHORT,
            ToolName.GENERATE_QUIZ_ESSAY,
            ToolName.GENERATE_QUIZ_FLASH,
        ):
            quiz_type = _params_quiz_type(params, _default_quiz_type_for_tool(tool))
            learning_context = self._context_collector.collect(state, page_state)
            if not learning_context.has_page_text and not (page_state.explanation or "").strip():
                yield NdjsonEvent(
                    type=NdjsonEventType.AGENT_DELTA,
                    agent="quiz",
                    tool="GENERATE_QUIZ",
                    channel="main",
                    delta=_MSG_MISSING_QUIZ_CONTEXT,
                )
                yield NdjsonEvent(
                    type=NdjsonEventType.DONE,
                    agent="quiz",
                    tool="GENERATE_QUIZ",
                    final=True,
                    data={
                        "quiz": [],
                        "source": "FALLBACK",
                        "fallbackUsed": True,
                        "reason": "MISSING_CONTEXT",
                        "confidence": "LOW",
                        "warnings": ["MISSING_CONTEXT"],
                    },
                )
                return
            lecture_content = learning_context.build_quiz_context(page_state.explanation)
            count = params.get("count", 5)
            profile = params.get("profile")

            async for event in self._quiz.run_stream(
                quiz_type,
                lecture_content,
                profile,
                state.learner.model_dump(),
                count,
            ):
                if event.type == NdjsonEventType.DONE and event.data:
                    quiz_data = event.data.get("quiz")
                    if quiz_data:
                        record = QuizRecord(
                            quiz_id=str(uuid.uuid4()),
                            page_number=state.current_page,
                            quiz_type=quiz_type,
                            questions=quiz_data if isinstance(quiz_data, list) else [],
                        )
                        state.quiz_history.append(record)
                yield event

            page_state.status = PageStatus.QUIZ_IN_PROGRESS

        # ----------------------------------------------------------------
        # AUTO_GRADE_MCQ_OX
        # ----------------------------------------------------------------
        elif tool == ToolName.AUTO_GRADE_MCQ_OX:
            quiz_record = self._get_latest_quiz_record(state)
            user_answers = params.get("answers", [])
            quiz_type = _params_quiz_type(params, quiz_record.quiz_type if quiz_record else "Five_Choice")

            if quiz_record:
                async for event in self._grader.run_stream(
                    quiz_type,
                    quiz_record.questions,
                    user_answers,
                ):
                    if event.type == NdjsonEventType.DONE and event.data:
                        grading = event.data.get("grading", {})
                        assessment = self._apply_grading_to_record(quiz_record, grading, user_answers, state)
                        event.data = self._with_assessment_data(event.data, assessment, state)
                        if not event.data.get("passed"):
                            yield NdjsonEvent(
                                type=NdjsonEventType.DONE,
                                agent="grader",
                                final=True,
                                data={**event.data, "ui": {"widget": "REVIEW_DECISION"}},
                            )
                            continue
                    yield event
            else:
                yield NdjsonEvent(
                    type=NdjsonEventType.ERROR,
                    agent="grader",
                    message=_MSG_NO_QUIZ_RECORD,
                )

        # ----------------------------------------------------------------
        # GRADE_SHORT_OR_ESSAY
        # ----------------------------------------------------------------
        elif tool == ToolName.GRADE_SHORT_OR_ESSAY:
            quiz_record = self._get_latest_quiz_record(state)
            user_answers = params.get("answers", [])
            quiz_type = _params_quiz_type(params, quiz_record.quiz_type if quiz_record else "Short_Answer")
            learning_context = self._context_collector.collect(state, page_state)
            lecture_content = learning_context.build_quiz_context(page_state.explanation)
            pdf_path = page_state.pdf_path or state.pdf_path or None

            if quiz_record:
                async for event in self._grader.run_stream(
                    quiz_type,
                    quiz_record.questions,
                    user_answers,
                    lecture_content,
                    pdf_path,
                ):
                    if event.type == NdjsonEventType.DONE and event.data:
                        grading = event.data.get("grading", {})
                        assessment = self._apply_grading_to_record(quiz_record, grading, user_answers, state)
                        event.data = self._with_assessment_data(event.data, assessment, state)
                        if not event.data.get("passed"):
                            yield NdjsonEvent(
                                type=NdjsonEventType.DONE,
                                agent="grader",
                                final=True,
                                data={**event.data, "ui": {"widget": "REVIEW_DECISION"}},
                            )
                            continue
                    yield event
            else:
                yield NdjsonEvent(
                    type=NdjsonEventType.ERROR,
                    agent="grader",
                    message=_MSG_NO_QUIZ_RECORD,
                )

        # ----------------------------------------------------------------
        # REPAIR_MISCONCEPTION
        # ----------------------------------------------------------------
        elif tool == ToolName.REPAIR_MISCONCEPTION:
            student_message = params.get("student_message") or params.get("text") or params.get("question") or ""
            intervention = quiz_diagnosis_service.start_repair(state, str(student_message))
            if not intervention:
                yield NdjsonEvent(
                    type=NdjsonEventType.ERROR,
                    agent="repair",
                    tool="REPAIR_MISCONCEPTION",
                    message="No active intervention found for misconception repair.",
                )
                return

            learning_context = self._context_collector.collect(state, page_state)
            answer_chunks: List[str] = []
            repair_failed = False
            async for event in self._repair.run_stream(
                student_message=str(student_message),
                intervention=intervention,
                learning_context=learning_context,
            ):
                if event.type == NdjsonEventType.ERROR:
                    repair_failed = True
                if event.type == NdjsonEventType.AGENT_DELTA and event.channel == "main" and event.delta:
                    answer_chunks.append(event.delta)
                if event.type == NdjsonEventType.DONE:
                    continue
                yield event

            full_text = "".join(answer_chunks)
            if repair_failed or not full_text.strip():
                intervention["status"] = "AWAITING_USER_RESPONSE"
                intervention["lastFailureReason"] = "REPAIR_GENERATION_FAILED"
                intervention["updatedAt"] = datetime.now(timezone.utc).isoformat()
                if not repair_failed:
                    yield NdjsonEvent(
                        type=NdjsonEventType.AGENT_DELTA,
                        agent="repair",
                        tool="REPAIR_MISCONCEPTION",
                        channel="main",
                        delta=_MSG_REPAIR_FAILED,
                    )
                    yield NdjsonEvent(
                        type=NdjsonEventType.ERROR,
                        agent="repair",
                        tool="REPAIR_MISCONCEPTION",
                        code="REPAIR_GENERATION_FAILED",
                        message=_MSG_REPAIR_FAILED,
                        details=[{"errorType": "AI_UNAVAILABLE"}],
                    )
                yield NdjsonEvent(
                    type=NdjsonEventType.DONE,
                    agent="repair",
                    tool="REPAIR_MISCONCEPTION",
                    final=True,
                    data={
                        "activeIntervention": intervention,
                        "source": "FALLBACK",
                        "fallbackUsed": True,
                        "reason": "REPAIR_GENERATION_FAILED",
                        "confidence": "LOW",
                        "warnings": ["REPAIR_GENERATION_FAILED"],
                    },
                )
                return

            completed = quiz_diagnosis_service.complete_repair(state, full_text)
            if full_text:
                qa_thread_service.append_turn(
                    state,
                    page_number=learning_context.page_number,
                    question=str(student_message),
                    answer=full_text,
                    metadata={"agent": "repair", "interventionId": intervention.get("interventionId")},
                )
                state.append_message("assistant", full_text[:2000], {"agent": "repair"})
            yield NdjsonEvent(
                type=NdjsonEventType.DONE,
                agent="repair",
                tool="REPAIR_MISCONCEPTION",
                final=True,
                data={
                    "activeIntervention": completed or intervention,
                    "ui": {"widget": "RETEST_DECISION"},
                },
            )

        # ----------------------------------------------------------------
        # WRITE_FEEDBACK_ENTRY
        # ----------------------------------------------------------------
        elif tool == ToolName.WRITE_FEEDBACK_ENTRY:
            feedback_text = params.get("feedback", "")
            state.append_message("system", feedback_text, {"role": "feedback"})
            yield NdjsonEvent(
                type=NdjsonEventType.DONE,
                agent="system",
                tool="WRITE_FEEDBACK_ENTRY",
                final=True,
                data={"feedback_written": True},
            )

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _get_latest_quiz_record(self, state: SessionState) -> Optional[QuizRecord]:
        page_records = [
            r for r in reversed(state.quiz_history)
            if r.page_number == state.current_page
        ]
        return page_records[0] if page_records else None

    def _apply_grading_to_record(
        self,
        record: QuizRecord,
        grading: Dict[str, Any],
        user_answers: List[Any],
        state: SessionState,
    ) -> Dict[str, Any]:
        record.user_answers = user_answers
        try:
            score = float(grading.get("total_score", 0.0) or 0.0)
        except (TypeError, ValueError):
            score = 0.0
        record.score = score
        record.passed = score >= 0.6
        record.graded_at = datetime.now(timezone.utc).isoformat()

        page_key = str(state.current_page)
        page_state = state.get_current_page_state()
        state.learner.record_quiz_result(
            page_key,
            record.score,
            [page_state.chapter_title] if page_state.chapter_title else [],
        )
        page_state.status = PageStatus.QUIZ_GRADED
        return quiz_diagnosis_service.record_assessment(state, record, grading, user_answers)

    @staticmethod
    def _with_assessment_data(
        data: Dict[str, Any],
        assessment: Dict[str, Any],
        state: SessionState,
    ) -> Dict[str, Any]:
        enriched = {**data, "quizAssessment": assessment}
        if state.active_intervention:
            enriched["activeIntervention"] = state.active_intervention
        return enriched


def _params_quiz_type(params: Dict[str, Any], default: str) -> str:
    return normalize_exam_type_string(
        params.get(
            "quiz_type",
            params.get("quizType", params.get("exam_type", params.get("examType", params.get("selectedType", default)))),
        )
    )


def _default_quiz_type_for_tool(tool: ToolName | None) -> str:
    return {
        ToolName.GENERATE_QUIZ_FIVE_CHOICE: "Five_Choice",
        ToolName.GENERATE_QUIZ_OX: "OX_Problem",
        ToolName.GENERATE_QUIZ_SHORT: "Short_Answer",
        ToolName.GENERATE_QUIZ_ESSAY: "Essay",
        ToolName.GENERATE_QUIZ_FLASH: "Flash_Card",
    }.get(tool, "Five_Choice")
