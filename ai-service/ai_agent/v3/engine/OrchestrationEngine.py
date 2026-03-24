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
from ai_agent.v3.engine.StateReducer import StateReducer
from ai_agent.v3.engine.ToolDispatcher import ToolDispatcher
from ai_agent.bridge.GeminiBridgeClient import GeminiBridgeClient
from ai_agent.types.domain import AppEvent, NdjsonEvent, NdjsonEventType, SessionState
from app.core.session_store import SessionStore

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
        self._orchestrator = Orchestrator()
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

            # 3. Build plan (Orchestrator)
            plan = self._orchestrator.run(event, state)

            if not plan.actions:
                yield NdjsonEvent(
                    type=NdjsonEventType.AGENT_DELTA,
                    agent="system",
                    channel="main",
                    delta="No actions to process.",
                )
                yield NdjsonEvent(type=NdjsonEventType.DONE, agent="system", final=True, data={})
                return

            # 4. Execute actions (ToolDispatcher)
            async for ndjson_event in self._dispatcher.dispatch(plan, state, event.payload):
                yield ndjson_event

        except Exception as exc:
            logger.error(
                f"[OrchestrationEngine] Error processing session_id={session_id}: {exc}",
                exc_info=True,
            )
            yield NdjsonEvent(
                type=NdjsonEventType.ERROR,
                agent="system",
                message=f"Server error: {exc}",
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
