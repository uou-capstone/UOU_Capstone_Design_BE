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
  WRITE_FEEDBACK_ENTRY   -> internal logic
"""
from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Any, AsyncGenerator, Dict, List, Optional

from ai_agent.v3.agents.ExplainerAgent import ExplainerAgent
from ai_agent.v3.agents.GraderAgent import GraderAgent
from ai_agent.v3.agents.QaAgent import QaAgent
from ai_agent.v3.agents.QuizAgents import QuizAgents
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

_MSG_NO_QUIZ_RECORD = "No quiz record found for grading."
_MSG_TOOL_FAILED = "[SYSTEM] Tool execution failed"


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

    async def dispatch(
        self,
        plan: OrchestratorPlan,
        state: SessionState,
        event_payload: Dict[str, Any],
    ) -> AsyncGenerator[NdjsonEvent, None]:
        """
        Executes plan.actions in order and streams NdjsonEvents.

        Args:
            plan: Execution plan produced by Orchestrator
            state: Current session state (modified in-place)
            event_payload: Original AppEvent.payload (extra params for agent calls)
        """
        for action in plan.actions:
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
                type=NdjsonEventType.AGENT_DELTA,
                agent="system",
                channel="main",
                delta=f"{_MSG_TOOL_FAILED} ({action.tool}): {exc}",
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
        _PAYLOAD_ALLOWED_KEYS = {"question", "answers", "quiz_type", "accept", "decision", "page_number"}
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
            page_number = state.current_page or 1

            # Collect explanation stream (cap at 2000 chars for Redis storage)
            answer_chunks: List[str] = []
            async for event in self._explainer.run_stream(page_number, pdf_path, chapter_title, detail):
                if event.type == NdjsonEventType.AGENT_DELTA and event.channel == "main" and event.delta:
                    answer_chunks.append(event.delta)
                yield event

            full_text = "".join(answer_chunks)
            page_state.explanation = full_text[:2000] if len(full_text) > 2000 else full_text
            page_state.status = PageStatus.EXPLAINED
            state.append_message("assistant", page_state.explanation)

        # ----------------------------------------------------------------
        # ANSWER_QUESTION
        # ----------------------------------------------------------------
        elif tool == ToolName.ANSWER_QUESTION:
            question = params.get("question", "")
            pdf_path = page_state.pdf_path or state.pdf_path or ""
            chapter_title = page_state.chapter_title

            async for event in self._qa.run_stream(question, pdf_path, chapter_title):
                yield event

        # ----------------------------------------------------------------
        # GENERATE_QUIZ_*
        # ----------------------------------------------------------------
        elif tool in (
            ToolName.GENERATE_QUIZ_FIVE_CHOICE,
            ToolName.GENERATE_QUIZ_OX,
            ToolName.GENERATE_QUIZ_SHORT,
            ToolName.GENERATE_QUIZ_FLASH,
        ):
            quiz_type = params.get("quiz_type", "Five_Choice")
            lecture_content = page_state.explanation or ""
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
            quiz_type = params.get("quiz_type", "Five_Choice")

            if quiz_record:
                async for event in self._grader.run_stream(
                    quiz_type,
                    quiz_record.questions,
                    user_answers,
                ):
                    if event.type == NdjsonEventType.DONE and event.data:
                        grading = event.data.get("grading", {})
                        self._apply_grading_to_record(quiz_record, grading, user_answers, state)
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
            quiz_type = params.get("quiz_type", "Short_Answer")
            lecture_content = page_state.explanation or ""
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
                        self._apply_grading_to_record(quiz_record, grading, user_answers, state)
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
    ) -> None:
        record.user_answers = user_answers
        record.score = grading.get("total_score", 0.0)
        record.passed = grading.get("total_score", 0.0) >= 0.6
        record.graded_at = datetime.now(timezone.utc).isoformat()

        page_key = str(state.current_page)
        page_state = state.get_current_page_state()
        state.learner.record_quiz_result(
            page_key,
            record.score,
            [page_state.chapter_title] if page_state.chapter_title else [],
        )
        page_state.status = PageStatus.QUIZ_GRADED
