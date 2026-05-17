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
                else:
                    yield ndjson_event

            if not plan:
                yield NdjsonEvent(
                    type=NdjsonEventType.AGENT_DELTA,
                    agent="system",
                    channel="main",
                    delta="No actions to process.",
                )
                yield NdjsonEvent(type=NdjsonEventType.DONE, agent="system", final=True, data={})
                return

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
                    delta="No actions to process.",
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
        if event.type != AppEventType.QUIZ_SUBMITTED:
            return None
        latest = self._latest_page_quiz(state)
        if not latest:
            return None
        quiz_type = normalize_exam_type_string(
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

    @staticmethod
    def _latest_page_quiz(state: SessionState):
        for record in reversed(state.quiz_history):
            if record.page_number == state.current_page:
                return record
        return None
