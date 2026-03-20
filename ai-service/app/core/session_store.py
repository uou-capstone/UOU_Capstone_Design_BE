"""
SessionStore

설계서 §13 확장포인트: JsonStore 를 DB/Redis 저장소로 교체 가능한 인터페이스.
현재 구현: Redis 기반 세션 영속화 (TTL 24시간)
"""
from __future__ import annotations

import logging
from typing import Optional

from ai_agent.types.domain import SessionState
from app.core.redis_client import redis_manager

logger = logging.getLogger(__name__)

SESSION_TTL = 86400  # 24시간


class SessionStore:
    """
    Redis 기반 세션 영속화 스토어.
    SessionState 를 JSON 직렬화하여 저장/조회한다.
    """

    def _key(self, session_id: int) -> str:
        return f"fa:session:{session_id}"

    async def get(self, session_id: int) -> Optional[SessionState]:
        """세션 조회. 없으면 None 반환."""
        try:
            client = redis_manager.get_client()
            raw = await client.get(self._key(session_id))
            if raw is None:
                return None
            return SessionState.model_validate_json(raw)
        except Exception as exc:
            logger.warning(f"[SessionStore] get 실패 session_id={session_id}: {exc}")
            return None

    async def set(self, state: SessionState) -> None:
        """세션 저장 (TTL 갱신)."""
        try:
            client = redis_manager.get_client()
            await client.set(
                self._key(state.session_id),
                state.model_dump_json(),
                ex=SESSION_TTL,
            )
        except Exception as exc:
            logger.warning(f"[SessionStore] set 실패 session_id={state.session_id}: {exc}")

    async def delete(self, session_id: int) -> None:
        """세션 삭제."""
        try:
            client = redis_manager.get_client()
            await client.delete(self._key(session_id))
        except Exception as exc:
            logger.warning(f"[SessionStore] delete 실패 session_id={session_id}: {exc}")

    async def get_or_create(self, session_id: int, lecture_id: int) -> SessionState:
        """세션 조회 또는 신규 생성."""
        from datetime import datetime, timezone

        state = await self.get(session_id)
        if state is None:
            now = datetime.now(timezone.utc).isoformat()
            state = SessionState(
                session_id=session_id,
                lecture_id=lecture_id,
                created_at=now,
                updated_at=now,
            )
            await self.set(state)
        return state


session_store = SessionStore()
