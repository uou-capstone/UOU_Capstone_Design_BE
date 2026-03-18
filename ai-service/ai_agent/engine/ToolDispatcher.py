"""
ToolDispatcher

설계서 §6: 오케스트레이터-서브에이전트 연결부.
OrchestratorPlan 의 액션을 실제 에이전트 호출로 변환한다.
도구 실패 시 SYSTEM 메시지로 degrade (soft-failure).

Tool → 실행 주체 매핑:
  EXPLAIN_PAGE           → ExplainerAgent
  ANSWER_QUESTION        → QaAgent
  GENERATE_QUIZ_*        → QuizAgents
  AUTO_GRADE_MCQ_OX      → 내부 로직 (GraderAgent._grade_auto)
  GRADE_SHORT_OR_ESSAY   → GraderAgent._grade_llm
  WRITE_FEEDBACK_ENTRY   → 내부 로직
"""
from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Any, AsyncGenerator, Dict, List, Optional

from ai_agent.agents.ExplainerAgent import ExplainerAgent
from ai_agent.agents.GraderAgent import GraderAgent
from ai_agent.agents.QaAgent import QaAgent
from ai_agent.agents.QuizAgents import QuizAgents
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


class ToolDispatcher:
    """
    OrchestratorPlan 을 받아 각 액션을 실행하고 NdjsonEvent 를 스트리밍한다.
    도구 실패는 SYSTEM 메시지로 degraded 처리하여 흐름을 유지한다.
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
        OrchestratorPlan 을 순서대로 실행하고 NdjsonEvent 를 스트리밍한다.

        Args:
            plan: Orchestrator 가 생성한 실행 계획
            state: 현재 세션 상태 (in-place 수정)
            event_payload: 원본 AppEvent.payload (에이전트 호출 시 추가 파라미터)
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
                delta=f"[SYSTEM] AI 도구 실행 실패 ({action.tool}): {exc}",
            )

    async def _execute_tool(
        self,
        action: OrchestratorAction,
        state: SessionState,
        event_payload: Dict[str, Any],
    ) -> AsyncGenerator[NdjsonEvent, None]:
        tool = action.tool
        params = {**action.params, **event_payload}
        page_state = state.get_current_page_state()

        # ----------------------------------------------------------------
        # EXPLAIN_PAGE
        # ----------------------------------------------------------------
        if tool == ToolName.EXPLAIN_PAGE:
            page_state.status = PageStatus.EXPLAINING
            detail = params.get("detail", "NORMAL")
            pdf_path = page_state.pdf_path or state.pdf_path or ""
            md_path = page_state.md_path
            chapter_title = page_state.chapter_title or f"페이지 {state.current_page}"

            # 설명 텍스트 수집 (Redis 저장용 요약 — 최대 2000자로 제한)
            answer_chunks: List[str] = []
            async for event in self._explainer.run_stream(chapter_title, pdf_path, md_path, detail):
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

            async for event in self._quiz.run_stream(quiz_type, lecture_content, profile, count):
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
                    message="채점할 퀴즈 기록을 찾을 수 없습니다.",
                )

        # ----------------------------------------------------------------
        # GRADE_SHORT_OR_ESSAY
        # ----------------------------------------------------------------
        elif tool == ToolName.GRADE_SHORT_OR_ESSAY:
            quiz_record = self._get_latest_quiz_record(state)
            user_answers = params.get("answers", [])
            quiz_type = params.get("quiz_type", "Short_Answer")
            lecture_content = page_state.explanation or ""

            if quiz_record:
                async for event in self._grader.run_stream(
                    quiz_type,
                    quiz_record.questions,
                    user_answers,
                    lecture_content,
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
                    message="채점할 퀴즈 기록을 찾을 수 없습니다.",
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
    # 내부 유틸
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
