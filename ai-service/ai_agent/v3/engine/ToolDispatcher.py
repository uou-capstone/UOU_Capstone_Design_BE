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
import re
from datetime import datetime, timezone
from typing import Any, AsyncGenerator, Dict, List, Optional

from ai_agent.v3.agents.ExplainerAgent import ExplainerAgent
from ai_agent.v3.agents.GraderAgent import GraderAgent, PASS_SCORE_RATIO
from ai_agent.v3.agents.MisconceptionRepairAgent import MisconceptionRepairAgent
from ai_agent.v3.agents.QaAgent import QaAgent
from ai_agent.v3.agents.QuizAgents import QuizAgents
from ai_agent.v3.engine.LearningContextCollector import LearningContextCollector
from ai_agent.v3.engine.PlanVerifier import PlanVerifier
from ai_agent.v3.engine.QaThreadService import qa_thread_service
from ai_agent.v3.engine.QuizDiagnosisService import quiz_diagnosis_service
from ai_agent.v3.engine.ThoughtTrace import (
    summarize_action,
    summarize_context,
    summarize_params,
    summarize_warnings,
    trace_event,
)
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
from app.services.pdf_file_ref_service import pdf_file_ref_service

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
        if trace := trace_event(
            "PlanVerifier 검증 완료\n"
            f"- inputActions={len(plan.actions)}\n"
            f"- outputActions={len(verification.plan.actions)}\n"
            f"- warnings={summarize_warnings(verification.warnings)}",
            agent="dispatcher",
        ):
            yield trace
        for index, action in enumerate(verification.plan.actions, start=1):
            if trace := trace_event(
                f"액션 실행 {index}/{len(verification.plan.actions)}\n"
                f"- {summarize_action(action)}",
                agent="dispatcher",
                tool=action.tool.value if action.tool else None,
            ):
                yield trace
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
                if trace := trace_event(
                    "SEND_MESSAGE 실행\n"
                    f"- messageChars={len(action.message or '')}\n"
                    f"- ui={summarize_params(action.ui_state)}",
                    agent="dispatcher",
                ):
                    yield trace
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
                if trace := trace_event(
                    "SET_UI_STATE 실행\n"
                    f"- ui={summarize_params(action.ui_state)}",
                    agent="dispatcher",
                ):
                    yield trace
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
            "quiz_id",
            "quizId",
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
            if trace := trace_event(
                "EXPLAIN_PAGE context 준비\n"
                f"- detail={detail}\n"
                f"- nextWidget={params.get('next_widget', 'NEXT_PAGE_DECISION')}\n"
                f"- {_trace_context_summary(learning_context)}",
                agent="explainer",
                tool=ToolName.EXPLAIN_PAGE.value,
            ):
                yield trace

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
                if trace := trace_event(
                    "EXPLAIN_PAGE 완료\n"
                    f"- answerChars={len(full_text)}\n"
                    f"- storedChars={len(page_state.explanation)}\n"
                    f"- nextWidget={next_widget}",
                    agent="explainer",
                    tool=ToolName.EXPLAIN_PAGE.value,
                ):
                    yield trace
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
            learning_context = self._context_collector.collect_for_question(state, str(question), page_state)
            pdf_original_part = await pdf_file_ref_service.ensure_file_part(
                self._bridge,
                state,
                pdf_path,
            )
            if trace := trace_event(
                "ANSWER_QUESTION context 준비\n"
                f"- question={str(question)[:120]!r}\n"
                f"- fileRef={'yes' if pdf_original_part is not None else 'no'}\n"
                f"- {_trace_context_summary(learning_context)}",
                agent="qa",
                tool=ToolName.ANSWER_QUESTION.value,
            ):
                yield trace

            answer_chunks: List[str] = []
            retry_without_file_ref = False
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
                related_pages_text=learning_context.related_pages_digest,
                pdf_original_part=pdf_original_part,
            ):
                if (
                    event.type == NdjsonEventType.ERROR
                    and pdf_original_part is not None
                    and not answer_chunks
                ):
                    await _invalidate_pdf_file_ref_cache(self._bridge, state, pdf_path)
                    retry_without_file_ref = True
                    if trace := trace_event(
                        "ANSWER_QUESTION fileRef 실패: text-only 재시도 예정",
                        agent="qa",
                        tool=ToolName.ANSWER_QUESTION.value,
                    ):
                        yield trace
                    break
                if event.type == NdjsonEventType.AGENT_DELTA and event.channel == "main" and event.delta:
                    answer_chunks.append(event.delta)
                if event.type == NdjsonEventType.DONE:
                    continue
                yield event

            if retry_without_file_ref:
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
                    related_pages_text=learning_context.related_pages_digest,
                    pdf_original_part=None,
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
            if trace := trace_event(
                "ANSWER_QUESTION 완료\n"
                f"- answerChars={len(full_text)}\n"
                "- nextWidget=NEXT_PAGE_DECISION",
                agent="qa",
                tool=ToolName.ANSWER_QUESTION.value,
            ):
                yield trace
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
            coverage_start_page = _optional_int_param(params, "coverage_start_page", "coverageStartPage", "startPage")
            coverage_end_page = _optional_int_param(params, "coverage_end_page", "coverageEndPage", "endPage")
            learning_context = self._context_collector.collect_for_quiz(
                state,
                page_state,
                coverage_start_page=coverage_start_page,
                coverage_end_page=coverage_end_page,
            )
            if not learning_context.has_page_text and not (page_state.explanation or "").strip():
                state.pending_quiz_request = None
                if trace := trace_event(
                    "GENERATE_QUIZ 중단\n"
                    f"- quizType={quiz_type}\n"
                    "- reason=MISSING_CONTEXT\n"
                    f"- {_trace_context_summary(learning_context)}",
                    agent="quiz",
                    tool="GENERATE_QUIZ",
                ):
                    yield trace
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
            count = _optional_int_param(params, "count", "questionCount", "numQuestions") or 5
            profile = params.get("profile")
            retest_context = _build_retest_generation_context(state, quiz_type, event_payload)
            if retest_context:
                lecture_content = _append_retest_generation_guidance(lecture_content, retest_context)
                profile = _merge_retest_generation_profile(profile, retest_context)
            context_label = _quiz_context_label(learning_context)
            if retest_context:
                context_label = f"{context_label} 재시험"
            if trace := trace_event(
                "GENERATE_QUIZ context 준비\n"
                f"- quizType={quiz_type}\n"
                f"- count={count}\n"
                f"- contextLabel={context_label}\n"
                f"- retest={'yes' if retest_context else 'no'}\n"
                f"- {_trace_context_summary(learning_context)}",
                agent="quiz",
                tool="GENERATE_QUIZ",
            ):
                yield trace

            generated = False
            max_generation_attempts = 2 if retest_context else 1
            for generation_attempt in range(max_generation_attempts):
                attempt_lecture_content = lecture_content
                if retest_context and generation_attempt > 0:
                    attempt_lecture_content = _append_retest_retry_guidance(lecture_content)
                retry_retest_generation = False
                duplicate_retest_generation = False

                async for event in self._quiz.run_stream(
                    quiz_type,
                    attempt_lecture_content,
                    profile,
                    state.learner.model_dump(),
                    count,
                    context_label=context_label,
                ):
                    if event.type == NdjsonEventType.DONE and event.data:
                        quiz_data = event.data.get("quiz")
                        if retest_context and quiz_data and _quiz_reuses_previous_questions(quiz_data, retest_context):
                            duplicate_retest_generation = True
                            retry_retest_generation = generation_attempt < max_generation_attempts - 1
                            if trace := trace_event(
                                "GENERATE_QUIZ 재시험 중복 문항 감지\n"
                                f"- attempt={generation_attempt + 1}\n"
                                f"- retry={'yes' if retry_retest_generation else 'no'}",
                                agent="quiz",
                                tool="GENERATE_QUIZ",
                            ):
                                yield trace
                            break

                        if quiz_data:
                            record = QuizRecord(
                                quiz_id=str(uuid.uuid4()),
                                page_number=state.current_page,
                                quiz_type=quiz_type,
                                questions=quiz_data if isinstance(quiz_data, list) else [],
                                coverage_start_page=learning_context.coverage_start_page,
                                coverage_end_page=learning_context.coverage_end_page,
                                source_request=str(params.get("source_request") or params.get("sourceRequest") or "") or None,
                            )
                            state.quiz_history.append(record)
                            if trace := trace_event(
                                "GENERATE_QUIZ 결과 저장\n"
                                f"- quizId={record.quiz_id}\n"
                                f"- quizType={record.quiz_type}\n"
                                f"- questions={len(record.questions)}\n"
                                f"- coverage={record.coverage_start_page or record.page_number}-{record.coverage_end_page or record.page_number}",
                                agent="quiz",
                                tool="GENERATE_QUIZ",
                            ):
                                yield trace
                    yield event

                if duplicate_retest_generation and retry_retest_generation:
                    continue
                if duplicate_retest_generation:
                    break
                generated = True
                break

            if retest_context and not generated:
                yield NdjsonEvent(
                    type=NdjsonEventType.ERROR,
                    agent="quiz",
                    code="RETEST_QUIZ_DUPLICATE_GENERATION_FAILED",
                    message="재시험 문항이 기존 문항과 반복되어 생성에 실패했습니다. 다시 시도해 주세요.",
                    details=[{"reason": "DUPLICATE_RETEST_QUESTIONS"}],
                )
                return

            page_state.status = PageStatus.QUIZ_IN_PROGRESS
            state.pending_quiz_request = None

        # ----------------------------------------------------------------
        # AUTO_GRADE_MCQ_OX
        # ----------------------------------------------------------------
        elif tool == ToolName.AUTO_GRADE_MCQ_OX:
            user_answers = params.get("answers", [])
            requested_quiz_type = _params_quiz_type(params, "")
            quiz_record = self._get_latest_quiz_record(
                state,
                quiz_type=requested_quiz_type,
                event_payload=event_payload,
            )
            quiz_type = _params_quiz_type(params, quiz_record.quiz_type if quiz_record else "Five_Choice")

            if quiz_record:
                if trace := trace_event(
                    "AUTO_GRADE_MCQ_OX 시작\n"
                    f"- quizType={quiz_type}\n"
                    f"- quizId={quiz_record.quiz_id}\n"
                    f"- questions={len(quiz_record.questions)}\n"
                    f"- answers={len(user_answers) if isinstance(user_answers, list) else 0}\n"
                    f"- passScoreRatio={PASS_SCORE_RATIO}",
                    agent="grader",
                    tool=ToolName.AUTO_GRADE_MCQ_OX.value,
                ):
                    yield trace
                async for event in self._grader.run_stream(
                    quiz_type,
                    quiz_record.questions,
                    user_answers,
                ):
                    if event.type == NdjsonEventType.DONE and event.data:
                        grading = event.data.get("grading", {})
                        assessment = self._apply_grading_to_record(quiz_record, grading, user_answers, state)
                        event.data = self._with_assessment_data(event.data, assessment, state, quiz_record)
                        if trace := trace_event(
                            "AUTO_GRADE_MCQ_OX 결과\n"
                            f"- score={event.data.get('total_score')}\n"
                            f"- passed={event.data.get('passed')}\n"
                            f"- assessmentStatus={assessment.get('status')}\n"
                            f"- nextWidget={'REVIEW_DECISION' if not event.data.get('passed') else 'NEXT_PAGE_DECISION'}",
                            agent="grader",
                            tool=ToolName.AUTO_GRADE_MCQ_OX.value,
                        ):
                            yield trace
                        if not event.data.get("passed"):
                            diagnostic_prompt = _active_diagnostic_prompt(state)
                            if diagnostic_prompt:
                                yield NdjsonEvent(
                                    type=NdjsonEventType.AGENT_DELTA,
                                    agent="orchestrator",
                                    channel="main",
                                    delta=diagnostic_prompt,
                                )
                            yield NdjsonEvent(
                                type=NdjsonEventType.DONE,
                                agent="grader",
                                final=True,
                                data={**event.data, "ui": {"widget": "REVIEW_DECISION"}},
                            )
                            continue
                        event.data = self._with_passed_followup_data(event.data)
                        followup_message = self._build_passed_grading_message(event.data)
                        if followup_message:
                            yield NdjsonEvent(
                                type=NdjsonEventType.AGENT_DELTA,
                                agent="grader",
                                tool=ToolName.AUTO_GRADE_MCQ_OX.value,
                                channel="main",
                                delta=followup_message,
                            )
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
            user_answers = params.get("answers", [])
            requested_quiz_type = _params_quiz_type(params, "")
            quiz_record = self._get_latest_quiz_record(
                state,
                quiz_type=requested_quiz_type,
                event_payload=event_payload,
            )
            quiz_type = _params_quiz_type(params, quiz_record.quiz_type if quiz_record else "Short_Answer")
            learning_context = self._context_collector.collect(state, page_state)
            lecture_content = learning_context.build_quiz_context(page_state.explanation)
            pdf_path = page_state.pdf_path or state.pdf_path or None

            if quiz_record:
                if trace := trace_event(
                    "GRADE_SHORT_OR_ESSAY 시작\n"
                    f"- quizType={quiz_type}\n"
                    f"- quizId={quiz_record.quiz_id}\n"
                    f"- questions={len(quiz_record.questions)}\n"
                    f"- answers={len(user_answers) if isinstance(user_answers, list) else 0}",
                    agent="grader",
                    tool=ToolName.GRADE_SHORT_OR_ESSAY.value,
                ):
                    yield trace
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
                        event.data = self._with_assessment_data(event.data, assessment, state, quiz_record)
                        if trace := trace_event(
                            "GRADE_SHORT_OR_ESSAY 결과\n"
                            f"- score={event.data.get('total_score')}\n"
                            f"- passed={event.data.get('passed')}\n"
                            f"- assessmentStatus={assessment.get('status')}\n"
                            f"- nextWidget={'REVIEW_DECISION' if not event.data.get('passed') else 'NEXT_PAGE_DECISION'}",
                            agent="grader",
                            tool=ToolName.GRADE_SHORT_OR_ESSAY.value,
                        ):
                            yield trace
                        if not event.data.get("passed"):
                            diagnostic_prompt = _active_diagnostic_prompt(state)
                            if diagnostic_prompt:
                                yield NdjsonEvent(
                                    type=NdjsonEventType.AGENT_DELTA,
                                    agent="orchestrator",
                                    channel="main",
                                    delta=diagnostic_prompt,
                                )
                            yield NdjsonEvent(
                                type=NdjsonEventType.DONE,
                                agent="grader",
                                final=True,
                                data={**event.data, "ui": {"widget": "REVIEW_DECISION"}},
                            )
                            continue
                        event.data = self._with_passed_followup_data(event.data)
                        followup_message = self._build_passed_grading_message(event.data)
                        if followup_message:
                            yield NdjsonEvent(
                                type=NdjsonEventType.AGENT_DELTA,
                                agent="grader",
                                tool=ToolName.GRADE_SHORT_OR_ESSAY.value,
                                channel="main",
                                delta=followup_message,
                            )
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
            if trace := trace_event(
                "REPAIR_MISCONCEPTION 시작\n"
                f"- studentMessage={str(student_message)[:120]!r}\n"
                f"- interventionId={intervention.get('interventionId')}\n"
                f"- focus={', '.join(intervention.get('focusConcepts') or intervention.get('focus_concepts') or []) or 'none'}\n"
                f"- {_trace_context_summary(learning_context)}",
                agent="repair",
                tool=ToolName.REPAIR_MISCONCEPTION.value,
            ):
                yield trace
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
            if trace := trace_event(
                "REPAIR_MISCONCEPTION 완료\n"
                f"- answerChars={len(full_text)}\n"
                "- nextWidget=RETEST_DECISION",
                agent="repair",
                tool=ToolName.REPAIR_MISCONCEPTION.value,
            ):
                yield trace
            yield NdjsonEvent(
                type=NdjsonEventType.DONE,
                agent="repair",
                tool="REPAIR_MISCONCEPTION",
                final=True,
                data={
                    "activeIntervention": completed or intervention,
                    "learningEvidence": _build_repair_learning_evidence(state, completed or intervention),
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

    def _get_latest_quiz_record(
        self,
        state: SessionState,
        *,
        quiz_type: str | None = None,
        event_payload: Dict[str, Any] | None = None,
    ) -> Optional[QuizRecord]:
        records = list(reversed(state.quiz_history))
        if not records:
            return None

        target_quiz_id = _payload_quiz_id(event_payload or {})
        if target_quiz_id:
            for record in records:
                if str(record.quiz_id) == target_quiz_id:
                    return record

        normalized_type = normalize_exam_type_string(str(quiz_type or "").strip())

        def type_matches(record: QuizRecord) -> bool:
            return not normalized_type or normalize_exam_type_string(record.quiz_type) == normalized_type

        for record in records:
            if record.page_number == state.current_page and record.score is None and type_matches(record):
                return record
        for record in records:
            if record.score is None and type_matches(record):
                return record
        for record in records:
            if record.page_number == state.current_page and type_matches(record):
                return record
        for record in records:
            if type_matches(record):
                return record
        for record in records:
            if record.page_number == state.current_page:
                return record
        return records[0]

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
        record.passed = score >= PASS_SCORE_RATIO
        record.graded_at = datetime.now(timezone.utc).isoformat()

        page_key = str(state.current_page)
        page_state = state.get_current_page_state()
        state.learner.record_quiz_result(
            page_key,
            record.score,
            [page_state.chapter_title] if page_state.chapter_title else [],
        )
        page_state.status = PageStatus.QUIZ_GRADED
        was_retest = (
            state.active_intervention is not None
            and int(state.active_intervention.get("pageNumber") or 0) == record.page_number
        )
        assessment = quiz_diagnosis_service.record_assessment(state, record, grading, user_answers)
        assessment["eventType"] = "RETEST_GRADED" if was_retest else "QUIZ_GRADED"
        return assessment

    @staticmethod
    def _with_assessment_data(
        data: Dict[str, Any],
        assessment: Dict[str, Any],
        state: SessionState,
        record: QuizRecord | None = None,
    ) -> Dict[str, Any]:
        enriched = {
            **data,
            "quizAssessment": assessment,
            "passScoreRatio": PASS_SCORE_RATIO,
        }
        if record is not None:
            enriched["learningEvidence"] = _build_quiz_learning_evidence(
                state=state,
                record=record,
                data=data,
                assessment=assessment,
            )
        if state.active_intervention:
            enriched["activeIntervention"] = state.active_intervention
        return enriched

    @staticmethod
    def _with_passed_followup_data(data: Dict[str, Any]) -> Dict[str, Any]:
        if data.get("passed") is True and "ui" not in data:
            return {**data, "ui": {"widget": "NEXT_PAGE_DECISION"}}
        return data

    @staticmethod
    def _build_passed_grading_message(data: Dict[str, Any]) -> str:
        if data.get("passed") is not True:
            return ""

        grading = data.get("grading") if isinstance(data.get("grading"), dict) else {}
        total_score = _safe_float(data.get("total_score", grading.get("total_score")))
        pass_ratio = _safe_float(data.get("passScoreRatio", PASS_SCORE_RATIO))
        overall_feedback = str(grading.get("overall_feedback") or "").strip()
        results = grading.get("results") if isinstance(grading.get("results"), list) else []
        missed_items = [
            item for item in results
            if isinstance(item, dict) and not _result_item_passed(item)
        ]

        lines = [
            f"채점 결과, 기준 점수 {pass_ratio * 100:.0f}% 이상이라 통과입니다. "
            f"이번 점수는 {total_score * 100:.0f}%입니다."
        ]
        if overall_feedback:
            lines.append(overall_feedback)

        if missed_items:
            lines.extend([
                "",
                "다만 틀린 문항은 다음 페이지로 넘어가기 전에 짧게 짚고 갈게요.",
            ])
            for item in missed_items[:3]:
                index = _display_question_index(item)
                feedback = str(item.get("feedback") or "").strip()
                user_answer = _stringify_answer(item.get("user_answer"))
                correct_answer = _stringify_answer(item.get("correct_answer"))

                parts = [f"- {index}번 문항"]
                if user_answer or correct_answer:
                    answer_bits = []
                    if user_answer:
                        answer_bits.append(f"내 답: {user_answer}")
                    if correct_answer:
                        answer_bits.append(f"정답: {correct_answer}")
                    parts.append(f"({', '.join(answer_bits)})")
                if feedback:
                    parts.append(f": {feedback}")
                lines.append(" ".join(parts))

            if len(missed_items) > 3:
                lines.append(f"- 그 외 {len(missed_items) - 3}개 문항도 채점 결과에서 확인해 주세요.")
            lines.append("")
            lines.append("이 부분만 확인하고 다음 페이지로 넘어가면 됩니다.")
        else:
            lines.append("틀린 문항이 없어 현재 페이지 핵심을 충분히 이해한 상태로 볼 수 있습니다.")

        return "\n".join(lines).strip()


def _params_quiz_type(params: Dict[str, Any], default: str) -> str:
    return normalize_exam_type_string(
        params.get(
            "quiz_type",
            params.get("quizType", params.get("exam_type", params.get("examType", params.get("selectedType", default)))),
        )
    )


def _payload_quiz_id(payload: Dict[str, Any]) -> str:
    for key in ("quiz_id", "quizId", "id"):
        value = payload.get(key)
        if value is None:
            continue
        text = str(value).strip()
        if text:
            return text
    return ""


def _default_quiz_type_for_tool(tool: ToolName | None) -> str:
    return {
        ToolName.GENERATE_QUIZ_FIVE_CHOICE: "Five_Choice",
        ToolName.GENERATE_QUIZ_OX: "OX_Problem",
        ToolName.GENERATE_QUIZ_SHORT: "Short_Answer",
        ToolName.GENERATE_QUIZ_ESSAY: "Essay",
        ToolName.GENERATE_QUIZ_FLASH: "Flash_Card",
    }.get(tool, "Five_Choice")


def _optional_int_param(params: Dict[str, Any], *keys: str) -> int | None:
    for key in keys:
        if key not in params:
            continue
        value = params.get(key)
        if value is None or value == "":
            continue
        try:
            return int(value)
        except (TypeError, ValueError):
            return None
    return None


def _quiz_context_label(learning_context) -> str:
    start = getattr(learning_context, "coverage_start_page", None)
    end = getattr(learning_context, "coverage_end_page", None)
    if start and end and start != end:
        return f"{start}~{end}페이지"
    return "현재 페이지"


def _trace_context_summary(learning_context) -> str:
    return summarize_context(
        page_number=int(getattr(learning_context, "page_number", 1) or 1),
        page_count=int(getattr(learning_context, "page_count", 0) or 0),
        page_text=str(getattr(learning_context, "page_text", "") or ""),
        prev_text=str(getattr(learning_context, "prev_text", "") or ""),
        next_text=str(getattr(learning_context, "next_text", "") or ""),
        related_pages_digest=str(getattr(learning_context, "related_pages_digest", "") or ""),
        qa_thread_digest=str(getattr(learning_context, "qa_thread_digest", "") or ""),
        coverage_start_page=getattr(learning_context, "coverage_start_page", None),
        coverage_end_page=getattr(learning_context, "coverage_end_page", None),
    )


def _safe_float(value: Any, default: float = 0.0) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def _result_item_passed(item: Dict[str, Any]) -> bool:
    if "passed" in item:
        return item.get("passed") is True
    score = _safe_float(item.get("score"), 0.0)
    return score >= 1.0


def _display_question_index(item: Dict[str, Any]) -> int:
    raw_index = item.get("question_index", item.get("questionIndex"))
    try:
        return int(raw_index) + 1
    except (TypeError, ValueError):
        return 1


def _stringify_answer(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, dict):
        for key in ("answer", "value", "text", "content"):
            nested = value.get(key)
            if nested is not None:
                return _stringify_answer(nested)
        return ""
    if isinstance(value, list):
        return ", ".join(str(item).strip() for item in value if str(item).strip())
    return str(value).strip()


def _build_quiz_learning_evidence(
    *,
    state: SessionState,
    record: QuizRecord,
    data: Dict[str, Any],
    assessment: Dict[str, Any],
) -> Dict[str, Any]:
    grading = data.get("grading") if isinstance(data.get("grading"), dict) else {}
    score_ratio = _safe_float(
        assessment.get("score", grading.get("total_score", data.get("total_score"))),
        0.0,
    )
    questions = record.questions if isinstance(record.questions, list) else []
    wrong_items = _normalize_wrong_items(assessment.get("missedQuestions"))
    focus_concepts = [
        str(item).strip()
        for item in (assessment.get("focusConcepts") or [])
        if str(item).strip()
    ]
    evidence_id = f"learning-{assessment.get('artifactId') or record.quiz_id}"
    event_type = str(assessment.get("eventType") or "QUIZ_GRADED")
    intervention = state.active_intervention if isinstance(state.active_intervention, dict) else None

    return {
        "type": "learning_evidence",
        "schemaVersion": "v1",
        "evidenceId": evidence_id,
        "eventType": event_type,
        "sessionId": state.session_id,
        "lectureId": state.lecture_id,
        "pageNumber": record.page_number,
        "coverage": {
            "startPage": record.coverage_start_page or record.page_number,
            "endPage": record.coverage_end_page or record.page_number,
            "sourceRequest": record.source_request,
        },
        "quiz": {
            "quizId": record.quiz_id,
            "quizType": record.quiz_type,
            "questionCount": len(questions),
        },
        "grading": {
            "score": round(score_ratio * len(questions), 3) if questions else round(score_ratio, 4),
            "total": len(questions) or None,
            "scoreRatio": round(score_ratio, 4),
            "passed": bool(assessment.get("passed")),
            "passScoreRatio": PASS_SCORE_RATIO,
            "wrongItems": wrong_items,
        },
        "diagnosis": {
            "weakConcepts": focus_concepts,
            "interventionStatus": intervention.get("status") if intervention else "NONE",
            "repairGoal": assessment.get("repairGoal"),
        },
        "createdAt": assessment.get("createdAt") or record.graded_at,
    }


def _build_repair_learning_evidence(
    state: SessionState,
    intervention: Dict[str, Any] | None,
) -> Dict[str, Any] | None:
    if not intervention:
        return None
    concepts = [
        str(item).strip()
        for item in (intervention.get("focusConcepts") or [])
        if str(item).strip()
    ]
    return {
        "type": "learning_evidence",
        "schemaVersion": "v1",
        "evidenceId": f"learning-repair-{intervention.get('interventionId') or uuid.uuid4().hex[:12]}",
        "eventType": "MISCONCEPTION_REPAIR_COMPLETED",
        "sessionId": state.session_id,
        "lectureId": state.lecture_id,
        "pageNumber": int(intervention.get("pageNumber") or state.current_page or 1),
        "coverage": {
            "startPage": int(intervention.get("pageNumber") or state.current_page or 1),
            "endPage": int(intervention.get("pageNumber") or state.current_page or 1),
            "sourceRequest": "오개념 교정",
        },
        "quiz": {
            "quizId": intervention.get("sourceQuizId"),
            "quizType": None,
            "questionCount": None,
        },
        "grading": {
            "score": None,
            "total": None,
            "scoreRatio": intervention.get("score"),
            "passed": False,
            "passScoreRatio": PASS_SCORE_RATIO,
            "wrongItems": [],
        },
        "diagnosis": {
            "weakConcepts": concepts or [str(intervention.get("focusConcept") or "현재 페이지 핵심 개념")],
            "interventionStatus": intervention.get("status"),
            "repairGoal": intervention.get("repairGoal"),
            "repairSummary": intervention.get("repairSummary"),
        },
        "createdAt": intervention.get("completedAt") or intervention.get("updatedAt") or intervention.get("createdAt"),
    }


def _build_retest_generation_context(
    state: SessionState,
    quiz_type: str,
    event_payload: Dict[str, Any] | None,
) -> Dict[str, Any] | None:
    if not _is_retest_generation_request(state, event_payload):
        return None

    intervention = state.active_intervention if isinstance(state.active_intervention, dict) else {}
    source_quiz_id = str(intervention.get("sourceQuizId") or "").strip()
    source_record = _find_quiz_record(state, source_quiz_id)
    source_assessment = _find_quiz_assessment(
        state,
        str(intervention.get("sourceArtifactId") or ""),
        source_quiz_id,
    )
    focus_concepts = _string_list(intervention.get("focusConcepts"))
    if not focus_concepts and intervention.get("focusConcept"):
        focus_concepts = [str(intervention.get("focusConcept")).strip()]
    if not focus_concepts and source_assessment:
        focus_concepts = _string_list(source_assessment.get("focusConcepts"))

    avoid_questions = _question_texts(source_record.questions if source_record else [])
    missed_questions = _missed_question_texts(source_assessment.get("missedQuestions") if source_assessment else [])
    avoid_questions = [item for item in dict.fromkeys([*missed_questions, *avoid_questions]) if item]

    return {
        "mode": "RETEST",
        "quizType": quiz_type,
        "sourceQuizId": source_quiz_id or None,
        "focusConcepts": focus_concepts[:8],
        "repairGoal": str(intervention.get("repairGoal") or "").strip() or None,
        "repairSummary": str(intervention.get("repairSummary") or "").strip() or None,
        "previousQuestions": avoid_questions[:10],
    }


def _is_retest_generation_request(
    state: SessionState,
    event_payload: Dict[str, Any] | None,
) -> bool:
    payload = event_payload or {}
    mode = str(
        payload.get("mode")
        or payload.get("quizMode")
        or payload.get("generationMode")
        or payload.get("reason")
        or ""
    ).strip().upper()
    if "RETEST" in mode:
        return True

    intervention = state.active_intervention if isinstance(state.active_intervention, dict) else None
    if not intervention:
        return False
    status = str(intervention.get("status") or "").strip().upper()
    if status in {"RESOLVED_BY_RETEST", "CANCELLED"}:
        return False
    if status not in {"COMPLETED", "REPAIR_COMPLETED", "READY_FOR_RETEST"}:
        return False
    page_number = int(intervention.get("pageNumber") or 0)
    return page_number == 0 or page_number == int(state.current_page or 1)


def _append_retest_generation_guidance(
    lecture_content: str,
    retest_context: Dict[str, Any],
) -> str:
    focus = ", ".join(_string_list(retest_context.get("focusConcepts"))) or "이번 오답 핵심 개념"
    previous_questions = _string_list(retest_context.get("previousQuestions"))
    avoid_block = "\n".join(f"- {item}" for item in previous_questions[:10]) or "- 이전 문항 없음"
    repair_goal = str(retest_context.get("repairGoal") or "").strip()
    repair_summary = str(retest_context.get("repairSummary") or "").strip()

    guidance = (
        "\n\n[재시험 출제 지시 - 사실 근거가 아니라 생성 제약]\n"
        "- 이 생성은 오개념 교정 이후의 재시험입니다.\n"
        "- 기존 시험보다 한 단계 쉬운 난이도로, 같은 개념을 더 직접적인 단서와 짧은 문장으로 다시 확인하세요.\n"
        "- 아래 기존 문항과 동일하거나 거의 같은 발문, OX 문장, 선택지 조합을 절대 재사용하지 마세요.\n"
        "- 정답 근거는 반드시 위 강의 자료에만 두고, 이 재시험 지시는 출제 방식 조절에만 사용하세요.\n"
        f"- 집중 개념: {focus}\n"
    )
    if repair_goal:
        guidance += f"- 교정 목표: {repair_goal}\n"
    if repair_summary:
        guidance += f"- 직전 교정 요약: {repair_summary[:500]}\n"
    guidance += "[기존 문항 재사용 금지 목록]\n" + avoid_block
    return f"{lecture_content}{guidance}"


def _append_retest_retry_guidance(lecture_content: str) -> str:
    return (
        f"{lecture_content}\n\n"
        "[재시험 재생성 지시]\n"
        "- 직전 생성 결과가 기존 문항과 너무 유사했습니다.\n"
        "- 같은 정답을 확인하더라도 발문 구조, 예시, 선택지/문장 표현을 완전히 바꾸세요.\n"
        "- 더 쉬운 확인 문제로 만들되, 기존 문항의 문장을 복사하거나 일부 단어만 바꾸지 마세요."
    )


def _quiz_reuses_previous_questions(
    quiz_data: Any,
    retest_context: Dict[str, Any],
) -> bool:
    previous = [_compact_question_text(item) for item in _string_list(retest_context.get("previousQuestions"))]
    previous = [item for item in previous if item]
    if not previous:
        return False

    generated = [_compact_question_text(item) for item in _question_texts(quiz_data)]
    generated = [item for item in generated if item]
    for new_item in generated:
        for old_item in previous:
            if new_item == old_item:
                return True
            if len(new_item) >= 24 and len(old_item) >= 24 and (new_item in old_item or old_item in new_item):
                return True
    return False


_QUESTION_COMPACT_RE = re.compile(r"[\s`'\".,!?…:;()\[\]{}<>·ㆍ|/\\_-]+")


def _compact_question_text(text: str) -> str:
    return _QUESTION_COMPACT_RE.sub("", str(text or "").strip()).lower()


def _merge_retest_generation_profile(
    profile: Any,
    retest_context: Dict[str, Any],
) -> Dict[str, Any]:
    profile_data = dict(profile) if isinstance(profile, dict) else {}
    learning_goal = dict(profile_data.get("learning_goal") or {})
    user_status = dict(profile_data.get("user_status") or {})
    feedback = dict(profile_data.get("feedback_preference") or {})

    focus_areas = _string_list(learning_goal.get("focus_areas"))
    for concept in _string_list(retest_context.get("focusConcepts")):
        if concept not in focus_areas:
            focus_areas.append(concept)

    learning_goal["focus_areas"] = focus_areas[:10]
    learning_goal["target_depth"] = "Concept"
    learning_goal["question_modality"] = learning_goal.get("question_modality") or "Theoretical"
    user_status["weakness_focus"] = True
    user_status["proficiency_level"] = _lower_retest_proficiency(user_status.get("proficiency_level"))
    feedback["strictness"] = "Lenient"
    feedback["explanation_depth"] = "Brief"

    profile_data["learning_goal"] = learning_goal
    profile_data["user_status"] = user_status
    profile_data["feedback_preference"] = feedback
    return profile_data


def _lower_retest_proficiency(raw: Any) -> str:
    value = str(raw or "").strip().lower()
    if value in {"advanced", "고급", "advance"}:
        return "Intermediate"
    return "Beginner"


def _find_quiz_record(state: SessionState, quiz_id: str) -> QuizRecord | None:
    if quiz_id:
        for record in reversed(state.quiz_history):
            if str(record.quiz_id) == quiz_id:
                return record
    intervention = state.active_intervention if isinstance(state.active_intervention, dict) else {}
    page_number = int(intervention.get("pageNumber") or state.current_page or 1)
    for record in reversed(state.quiz_history):
        if record.page_number == page_number and record.score is not None and record.passed is False:
            return record
    for record in reversed(state.quiz_history):
        if record.page_number == page_number:
            return record
    return None


def _find_quiz_assessment(
    state: SessionState,
    artifact_id: str,
    quiz_id: str,
) -> Dict[str, Any] | None:
    for item in reversed(state.quiz_assessments):
        if artifact_id and str(item.get("artifactId") or "") == artifact_id:
            return item
        if quiz_id and str(item.get("quizId") or "") == quiz_id:
            return item
    return None


def _question_texts(questions: Any) -> List[str]:
    if not isinstance(questions, list):
        return []
    return [_question_text(item) for item in questions if _question_text(item)]


def _missed_question_texts(items: Any) -> List[str]:
    if not isinstance(items, list):
        return []
    out: List[str] = []
    for item in items:
        if not isinstance(item, dict):
            continue
        text = _question_text(item)
        if text:
            out.append(text)
    return out


def _question_text(item: Any) -> str:
    if not isinstance(item, dict):
        return ""
    for key in (
        "question",
        "prompt",
        "question_content",
        "questionContent",
        "statement",
        "front",
        "frontContent",
        "content",
    ):
        value = item.get(key)
        if value is None:
            continue
        text = str(value).strip()
        if text:
            return text[:500]
    return ""


def _string_list(raw: Any) -> List[str]:
    if raw is None:
        return []
    if isinstance(raw, str):
        return [raw.strip()] if raw.strip() else []
    if not isinstance(raw, list):
        return []
    return [str(item).strip() for item in raw if str(item).strip()]


def _normalize_wrong_items(raw: Any) -> List[Dict[str, Any]]:
    if not isinstance(raw, list):
        return []
    out: List[Dict[str, Any]] = []
    for item in raw[:8]:
        if not isinstance(item, dict):
            continue
        out.append({
            "questionIndex": item.get("questionIndex", item.get("question_index")),
            "question": item.get("question"),
            "studentAnswer": item.get("userAnswer", item.get("user_answer")),
            "correctAnswer": item.get("correctAnswer", item.get("correct_answer")),
            "feedback": item.get("feedback"),
            "concepts": item.get("concepts") or [],
        })
    return out


def _active_diagnostic_prompt(state: SessionState) -> str:
    intervention = state.active_intervention or {}
    prompt = str(intervention.get("diagnosticPrompt") or "").strip()
    if prompt:
        return prompt
    focus = str(intervention.get("focusConcept") or "이번 퀴즈에서 틀린 부분").strip()
    return (
        f"이번에는 바로 전체 복습으로 가지 않고, **{focus}** 쪽에서 어디가 막혔는지 먼저 짚어볼게요.\n\n"
        "개념 자체가 헷갈렸는지, 적용 이유가 헷갈렸는지, 문제 풀이 과정이 헷갈렸는지 한 줄로 말해 주세요."
    )


async def _invalidate_pdf_file_ref_cache(
    bridge: GeminiBridgeClient,
    state: SessionState,
    pdf_path: str,
) -> None:
    invalidate = getattr(bridge, "invalidate_pdf_file_ref_cache", None)
    if callable(invalidate):
        await invalidate(pdf_path, fingerprint=state.pdf_fingerprint)
    state.gemini_file_ref = None
    state.pdf_fingerprint = None
