# -*- coding: utf-8 -*-
"""
OrchestrationEngine

Design doc section 3.3: Single-event processing pipeline.

Processing order:
1. Load session (SessionStore)
2. StateReducer.reduce() - apply state changes immediately
3. Orchestrator.run() - build action plan
4. ToolDispatcher.dispatch() - execute actions (streaming)
5. Save session
"""
from __future__ import annotations

import logging
from typing import AsyncGenerator, Optional

from ai_agent.v3.engine.NavigationIntentService import (
    NavigationDirective,
    navigation_intent_service,
)
from ai_agent.v3.engine.Orchestrator import Orchestrator
from ai_agent.v3.engine.QuizDiagnosisService import quiz_diagnosis_service
from ai_agent.v3.engine.StateReducer import StateReducer
from ai_agent.v3.engine.ToolDispatcher import ToolDispatcher
from ai_agent.bridge.GeminiBridgeClient import GeminiBridgeClient
from ai_agent.types.domain import (
    ActionType,
    AppEvent,
    AppEventType,
    NdjsonEvent,
    NdjsonEventType,
    OrchestratorAction,
    OrchestratorPlan,
    PageState,
    PageStatus,
    SessionState,
    ToolName,
)
from ai_agent.v3.exam_type_aliases import normalize_exam_type_string
from app.core.session_store import SessionStore
from app.services.error_mapping import stable_error_type

logger = logging.getLogger(__name__)


class OrchestrationEngine:
    """
    Central engine of the multi-agent orchestration layer.
    Called from FastAPI routers to process events and return NDJSON streams.
    """

    def __init__(
        self,
        session_store: SessionStore,
        bridge: Optional[GeminiBridgeClient] = None,
    ):
        self._store = session_store
        self._bridge = bridge or GeminiBridgeClient()
        self._reducer = StateReducer()
        self._orchestrator = Orchestrator(self._bridge)
        self._dispatcher = ToolDispatcher(self._bridge)

    async def handle_event_stream(
        self,
        session_id: int,
        lecture_id: int,
        event: AppEvent,
    ) -> AsyncGenerator[NdjsonEvent, None]:
        """
        Processes an event and streams NdjsonEvents.

        Args:
            session_id: Session ID
            lecture_id: Lecture ID
            event: AppEvent sent by the client
        """
        # 1. Load session
        state = await self._store.get_or_create(session_id, lecture_id)

        try:
            # 2. Apply state immediately (StateReducer)
            state = self._reducer.reduce(state, event)

            navigation_directive = navigation_intent_service.resolve(event, state)
            if navigation_directive:
                self._apply_navigation_directive(state, navigation_directive)
                yield NdjsonEvent(
                    type=NdjsonEventType.NAVIGATION,
                    targetPage=navigation_directive.target_page,
                    reason=navigation_directive.reason,
                    confidence=navigation_directive.confidence,
                    source=navigation_directive.source,
                )
                navigation_plan = self._navigation_plan(navigation_directive)
                async for ndjson_event in self._dispatcher.dispatch(
                    navigation_plan,
                    state,
                    event.payload,
                    event_type=event.type.value,
                ):
                    yield ndjson_event
                return

            fast_path_plan = self._fast_path_plan(event, state)
            if fast_path_plan:
                async for ndjson_event in self._dispatcher.dispatch(
                    fast_path_plan,
                    state,
                    event.payload,
                    event_type=event.type.value,
                ):
                    yield ndjson_event
                return

            # 3. Build plan via LLM (Orchestrator Stream)
            plan = None
            async for ndjson_event in self._orchestrator.run_stream(event, state):
                import ai_agent.types.domain as domain_models
                if ndjson_event.type == NdjsonEventType.DONE and ndjson_event.final:
                    if ndjson_event.data and "plan" in ndjson_event.data:
                        plan_data = ndjson_event.data["plan"]
                        if isinstance(plan_data, dict):
                            plan = domain_models.OrchestratorPlan(**plan_data)
                        else:
                            plan = plan_data
                elif ndjson_event.type == NdjsonEventType.AGENT_DELTA:
                    if ndjson_event.channel == "thought":
                        yield ndjson_event
                elif ndjson_event.type == NdjsonEventType.ERROR:
                    yield ndjson_event
                    return
                else:
                    yield ndjson_event

            if plan is None:
                plan = self._fallback_plan_for_empty_actions(event, state)

            if plan is None:
                yield NdjsonEvent(
                    type=NdjsonEventType.AGENT_DELTA,
                    agent="system",
                    channel="main",
                    delta="다음 학습 동작을 결정하지 못했습니다. 다시 시도해 주세요.",
                )
                yield NdjsonEvent(type=NdjsonEventType.DONE, agent="system", final=True, data={})
                return
            if not plan.actions:
                fallback_plan = self._fallback_plan_for_empty_actions(event, state)
                if fallback_plan is not None:
                    plan = fallback_plan

            # 4. Execute actions (ToolDispatcher)
            dispatched = False
            async for ndjson_event in self._dispatcher.dispatch(
                plan,
                state,
                event.payload,
                event_type=event.type.value,
            ):
                dispatched = True
                yield ndjson_event
            if not dispatched:
                yield NdjsonEvent(
                    type=NdjsonEventType.AGENT_DELTA,
                    agent="system",
                    channel="main",
                    delta="다음 학습 동작을 결정하지 못했습니다. 다시 시도해 주세요.",
                )
                yield NdjsonEvent(type=NdjsonEventType.DONE, agent="system", final=True, data={})

        except Exception as exc:
            logger.error(
                f"[OrchestrationEngine] Error processing session_id={session_id}: {exc}",
                exc_info=True,
            )
            yield NdjsonEvent(
                type=NdjsonEventType.ERROR,
                agent="system",
                code="SESSION_EVENT_FAILED",
                message="학습 세션 이벤트 처리 중 오류가 발생했습니다.",
                details=[{"errorType": stable_error_type(exc)}],
            )

        finally:
            # 5. Save session
            try:
                await self._store.set(state)
            except Exception as exc:
                logger.warning(f"[OrchestrationEngine] Session save failed: {exc}")

    async def handle_event(
        self,
        session_id: int,
        lecture_id: int,
        event: AppEvent,
    ) -> dict:
        """
        Non-streaming version: processes all events and returns a final result dict.
        """
        messages = []
        ui_patches = []
        data_patches = []

        async for ev in self.handle_event_stream(session_id, lecture_id, event):
            if ev.type == NdjsonEventType.AGENT_DELTA and ev.channel == "main" and ev.delta:
                messages.append(ev.delta)
            elif ev.type == NdjsonEventType.DONE and ev.data:
                if "ui" in ev.data:
                    ui_patches.append(ev.data["ui"])
                data_patches.append(ev.data)
            elif ev.type == NdjsonEventType.ERROR:
                return {"ok": False, "error": ev.message}

        return {
            "ok": True,
            "message": "".join(messages),
            "ui": ui_patches,
            "data": data_patches,
        }

    def _fast_path_plan(self, event: AppEvent, state: SessionState) -> OrchestratorPlan | None:
        if event.type == AppEventType.REVIEW_DECISION and _event_accepts(event):
            if state.active_intervention:
                return OrchestratorPlan(actions=[
                    OrchestratorAction(
                        type=ActionType.CALL_TOOL,
                        tool=ToolName.REPAIR_MISCONCEPTION,
                        params={"student_message": _event_message_text(event)},
                    )
                ])
            return OrchestratorPlan(actions=[
                OrchestratorAction(
                    type=ActionType.CALL_TOOL,
                    tool=ToolName.EXPLAIN_PAGE,
                    params={"detail": "DETAILED", "next_widget": "RETEST_DECISION"},
                )
            ])

        if event.type != AppEventType.QUIZ_SUBMITTED:
            return None
        latest = self._latest_page_quiz(state)
        if not latest:
            return None
        quiz_type = _normalize_quiz_type_text(
            event.get("quiz_type", event.get("quizType", event.get("exam_type", latest.quiz_type)))
        )
        if quiz_type not in {"Five_Choice", "OX_Problem"}:
            return None
        if quiz_diagnosis_service.has_pending_assessment(state, page_number=state.current_page):
            return None
        return OrchestratorPlan(actions=[
            OrchestratorAction(
                type=ActionType.CALL_TOOL,
                tool=ToolName.AUTO_GRADE_MCQ_OX,
                params={"quiz_type": quiz_type},
            )
        ])

    def _navigation_plan(self, directive: NavigationDirective) -> OrchestratorPlan:
        return OrchestratorPlan(actions=[
            OrchestratorAction(
                type=ActionType.CALL_TOOL,
                tool=ToolName.EXPLAIN_PAGE,
                params={"detail": "NORMAL", "next_widget": "NEXT_PAGE_DECISION"},
            )
        ])

    def _apply_navigation_directive(
        self,
        state: SessionState,
        directive: NavigationDirective,
    ) -> None:
        target_page = max(1, int(directive.target_page))
        if state.current_page != target_page:
            current_page_state = state.get_current_page_state()
            current_page_state.status = PageStatus.DONE
        state.current_page = target_page
        if state.current_page not in state.pages:
            state.pages[state.current_page] = PageState(page_number=state.current_page)

    def _fallback_plan_for_empty_actions(self, event: AppEvent, state: SessionState) -> OrchestratorPlan | None:
        if event.type == AppEventType.SESSION_ENTERED:
            return OrchestratorPlan(actions=[
                OrchestratorAction(
                    type=ActionType.SET_UI_STATE,
                    ui_state={"widget": "START_EXPLANATION_DECISION"},
                )
            ])
        if event.type == AppEventType.START_EXPLANATION_DECISION and _event_accepts(event):
            return OrchestratorPlan(actions=[
                OrchestratorAction(
                    type=ActionType.CALL_TOOL,
                    tool=ToolName.EXPLAIN_PAGE,
                    params={"detail": "NORMAL", "next_widget": "NEXT_PAGE_DECISION"},
                )
            ])
        if event.type == AppEventType.PAGE_CHANGED:
            return OrchestratorPlan(actions=[
                OrchestratorAction(
                    type=ActionType.CALL_TOOL,
                    tool=ToolName.EXPLAIN_PAGE,
                    params={"detail": "NORMAL", "next_widget": "QUIZ_DECISION"},
                )
            ])
        if event.type == AppEventType.USER_MESSAGE:
            question = str(event.get("question", event.get("text", ""))).strip()
            if question and not state.active_intervention:
                return OrchestratorPlan(actions=[
                    OrchestratorAction(
                        type=ActionType.CALL_TOOL,
                        tool=ToolName.ANSWER_QUESTION,
                        params={"question": question},
                    )
                ])
        if event.type == AppEventType.NEXT_PAGE_DECISION and _event_accepts(event):
            return OrchestratorPlan(actions=[
                OrchestratorAction(
                    type=ActionType.CALL_TOOL,
                    tool=ToolName.EXPLAIN_PAGE,
                    params={"detail": "NORMAL", "next_widget": "NEXT_PAGE_DECISION"},
                )
            ])
        if event.type == AppEventType.QUIZ_DECISION and _event_accepts(event):
            return OrchestratorPlan(actions=[
                OrchestratorAction(
                    type=ActionType.SET_UI_STATE,
                    ui_state={"modal": "QUIZ_TYPE_PICKER"},
                )
            ])
        if event.type == AppEventType.RETEST_DECISION and _event_accepts(event):
            return OrchestratorPlan(actions=[
                OrchestratorAction(
                    type=ActionType.SET_UI_STATE,
                    ui_state={"modal": "QUIZ_TYPE_PICKER", "mode": "RETEST"},
                )
            ])
        if event.type == AppEventType.QUIZ_TYPE_SELECTED:
            quiz_type = _event_quiz_type(event)
            tool = _quiz_generation_tool(quiz_type)
            if tool:
                return OrchestratorPlan(actions=[
                    OrchestratorAction(
                        type=ActionType.CALL_TOOL,
                        tool=tool,
                        params={"quiz_type": quiz_type},
                    )
                ])
        if event.type == AppEventType.QUIZ_SUBMITTED:
            quiz_type = _event_or_latest_quiz_type(event, state)
            if quiz_type in {"Short_Answer", "Essay"}:
                return OrchestratorPlan(actions=[
                    OrchestratorAction(
                        type=ActionType.CALL_TOOL,
                        tool=ToolName.GRADE_SHORT_OR_ESSAY,
                        params={"quiz_type": quiz_type},
                    )
                ])
        return None

    @staticmethod
    def _latest_page_quiz(state: SessionState):
        for record in reversed(state.quiz_history):
            if record.page_number == state.current_page:
                return record
        return None


def _event_accepts(event: AppEvent) -> bool:
    if not event.payload:
        return True
    value = event.get("accept", event.get("accepted", event.get("decision", event.get("start", True))))
    if isinstance(value, str):
        return value.strip().lower() in {"true", "yes", "y", "1", "accept", "accepted", "start", "next"}
    return bool(value)


def _event_message_text(event: AppEvent) -> str:
    return str(event.get("text", event.get("message", event.get("question", ""))).strip())


def _event_quiz_type(event: AppEvent) -> str:
    raw = event.get(
        "quiz_type",
        event.get(
            "quizType",
            event.get("exam_type", event.get("examType", event.get("selectedType", ""))),
        ),
    )
    return _normalize_quiz_type_text(raw)


def _event_or_latest_quiz_type(event: AppEvent, state: SessionState) -> str:
    latest = OrchestrationEngine._latest_page_quiz(state)
    raw = event.get(
        "quiz_type",
        event.get(
            "quizType",
            event.get("exam_type", event.get("examType", latest.quiz_type if latest else "")),
        ),
    )
    quiz_type = _normalize_quiz_type_text(raw)
    if quiz_type:
        return quiz_type
    return latest.quiz_type if latest else ""


def _normalize_quiz_type_text(raw: object) -> str:
    return normalize_exam_type_string(str(raw or "").strip())


def _quiz_generation_tool(quiz_type: str) -> ToolName | None:
    if quiz_type == "Five_Choice":
        return ToolName.GENERATE_QUIZ_FIVE_CHOICE
    if quiz_type == "OX_Problem":
        return ToolName.GENERATE_QUIZ_OX
    if quiz_type == "Short_Answer":
        return ToolName.GENERATE_QUIZ_SHORT
    if quiz_type == "Essay":
        return ToolName.GENERATE_QUIZ_ESSAY
    return None
