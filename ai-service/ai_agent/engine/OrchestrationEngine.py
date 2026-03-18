"""
OrchestrationEngine

설계서 §3.3: 단일 이벤트 처리 파이프라인.

처리 순서:
1. 세션 로드 (SessionStore)
2. StateReducer.reduce() 선반영
3. Orchestrator.run() 로 액션 플랜 생성
4. ToolDispatcher.dispatch() 로 액션 실행 (스트리밍)
5. 세션 저장
"""
from __future__ import annotations

import logging
from typing import AsyncGenerator, Optional

from ai_agent.engine.Orchestrator import Orchestrator
from ai_agent.engine.StateReducer import StateReducer
from ai_agent.engine.ToolDispatcher import ToolDispatcher
from ai_agent.bridge.GeminiBridgeClient import GeminiBridgeClient
from ai_agent.types.domain import AppEvent, NdjsonEvent, NdjsonEventType, SessionState
from app.core.session_store import SessionStore

logger = logging.getLogger(__name__)


class OrchestrationEngine:
    """
    멀티 에이전트 오케스트레이션의 중앙 엔진.
    FastAPI 라우터에서 호출되어 이벤트를 처리하고 NDJSON 스트림을 반환한다.
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
        이벤트를 처리하고 NdjsonEvent 를 스트리밍한다.

        Args:
            session_id: 세션 ID
            lecture_id: 강의 ID
            event: 클라이언트가 보낸 AppEvent
        """
        # 1. 세션 로드
        state = await self._store.get_or_create(session_id, lecture_id)

        try:
            # 2. 선반영 (StateReducer)
            state = self._reducer.reduce(state, event)

            # 3. 계획 수립 (Orchestrator)
            plan = self._orchestrator.run(event, state)

            if not plan.actions:
                yield NdjsonEvent(
                    type=NdjsonEventType.ANSWER_DELTA,
                    delta="처리할 액션이 없습니다.",
                )
                yield NdjsonEvent(type=NdjsonEventType.DONE, data={})
                return

            # 4. 액션 실행 (ToolDispatcher)
            async for ndjson_event in self._dispatcher.dispatch(plan, state, event.payload):
                yield ndjson_event

        except Exception as exc:
            logger.error(
                f"[OrchestrationEngine] 처리 중 오류 session_id={session_id}: {exc}",
                exc_info=True,
            )
            yield NdjsonEvent(
                type=NdjsonEventType.ERROR,
                message=f"서버 오류가 발생했습니다: {exc}",
            )

        finally:
            # 5. 세션 저장
            try:
                await self._store.set(state)
            except Exception as exc:
                logger.warning(f"[OrchestrationEngine] 세션 저장 실패: {exc}")

    async def handle_event(
        self,
        session_id: int,
        lecture_id: int,
        event: AppEvent,
    ) -> dict:
        """
        비스트리밍 버전: 모든 이벤트를 처리하고 최종 결과 딕셔너리를 반환한다.
        """
        messages = []
        ui_patches = []
        data_patches = []

        async for ev in self.handle_event_stream(session_id, lecture_id, event):
            if ev.type == NdjsonEventType.ANSWER_DELTA and ev.delta:
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
