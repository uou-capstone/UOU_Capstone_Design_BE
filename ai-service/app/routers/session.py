"""
Session Router

설계서 §3.2 API 진입점:
  GET  /api/v3/session/by-lecture/:lectureId  → 세션 조회/생성
  POST /api/v3/session/:sessionId/event       → 단건 이벤트 처리 (비스트리밍)
  POST /api/v3/session/:sessionId/event/stream → NDJSON 스트리밍 이벤트 처리
"""
from __future__ import annotations

import logging
from typing import Any, Dict, Optional

from fastapi import APIRouter, HTTPException, Path, Query
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from ai_agent.engine.OrchestrationEngine import OrchestrationEngine
from ai_agent.types.domain import AppEvent, AppEventType
from app.core.session_store import session_store

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v3/session", tags=["[v3] Session"])

_engine = OrchestrationEngine(session_store)


# ---------------------------------------------------------------------------
# 요청/응답 스키마
# ---------------------------------------------------------------------------

class EventRequest(BaseModel):
    """클라이언트 → 서버 이벤트 요청"""
    type: AppEventType
    lecture_id: Optional[int] = Field(
        default=None,
        description="강의 ID. 신규 세션 생성 시에만 필수, 기존 세션은 생략 가능",
    )
    payload: Dict[str, Any] = Field(default_factory=dict)


class SessionResponse(BaseModel):
    session_id: int
    lecture_id: int
    current_page: int
    ai_status_connected: bool
    created_at: Optional[str] = None
    updated_at: Optional[str] = None


# ---------------------------------------------------------------------------
# 엔드포인트
# ---------------------------------------------------------------------------

@router.get("/by-lecture/{lecture_id}", response_model=SessionResponse)
async def get_or_create_session(
    lecture_id: int = Path(..., description="강의 ID"),
    session_id: Optional[int] = Query(None, description="기존 세션 ID (없으면 신규 생성)"),
    pdf_path: Optional[str] = Query(None, description="PDF 파일 경로 (신규 세션일 때 설정)"),
):
    """
    강의에 대한 세션을 조회하거나 없으면 신규 생성합니다.
    PDF 파일이 없는 경우 ai_status_connected=false 를 반환합니다.
    """
    sid = session_id if session_id is not None else lecture_id

    state = await session_store.get_or_create(sid, lecture_id)

    if pdf_path and not state.pdf_path:
        state.pdf_path = pdf_path
        await session_store.set(state)

    return SessionResponse(
        session_id=state.session_id,
        lecture_id=state.lecture_id,
        current_page=state.current_page,
        ai_status_connected=bool(state.pdf_path),
        created_at=state.created_at,
        updated_at=state.updated_at,
    )


async def _resolve_lecture_id(session_id: int, req: EventRequest) -> int:
    """
    lecture_id 결정 로직.
    요청에 lecture_id가 있으면 그것을 사용하고,
    없으면 기존 세션에서 조회한다.
    """
    if req.lecture_id is not None:
        return req.lecture_id
    state = await session_store.get(session_id)
    if state is not None:
        return state.lecture_id
    raise HTTPException(
        status_code=400,
        detail="신규 세션 생성 시 lecture_id가 필요합니다.",
    )


@router.post("/{session_id}/event")
async def handle_event(
    session_id: int = Path(...),
    req: EventRequest = ...,
):
    """
    단건 이벤트 처리 (비스트리밍).
    전체 응답을 JSON 으로 반환합니다.
    """
    lecture_id = await _resolve_lecture_id(session_id, req)
    event = AppEvent(type=req.type, payload=req.payload)

    result = await _engine.handle_event(session_id, lecture_id, event)

    if not result.get("ok"):
        raise HTTPException(status_code=500, detail=result.get("error", "처리 실패"))

    return result


@router.post("/{session_id}/event/stream")
async def handle_event_stream(
    session_id: int = Path(...),
    req: EventRequest = ...,
):
    """
    NDJSON 스트리밍 이벤트 처리.
    각 줄은 독립적인 JSON 객체입니다.

    스트림 이벤트 포맷 (agent_delta 규격):
      {"type": "agent_delta", "agent": "explainer", "tool": "EXPLAIN_PAGE", "channel": "thought", "delta": "..."}
      {"type": "agent_delta", "agent": "explainer", "tool": "EXPLAIN_PAGE", "channel": "main",    "delta": "..."}
      {"type": "done",        "agent": "explainer", "tool": "EXPLAIN_PAGE", "final": true, "data": {...}}
      {"type": "error",       "agent": "system",    "message": "..."}
    """
    lecture_id = await _resolve_lecture_id(session_id, req)
    event = AppEvent(type=req.type, payload=req.payload)

    async def ndjson_generator():
        try:
            async for ndjson_event in _engine.handle_event_stream(
                session_id, lecture_id, event
            ):
                yield ndjson_event.to_ndjson_line()
        except Exception as exc:
            from ai_agent.types.domain import NdjsonEvent, NdjsonEventType
            err = NdjsonEvent(type=NdjsonEventType.ERROR, agent="system", message=str(exc))
            yield err.to_ndjson_line()

    return StreamingResponse(
        ndjson_generator(),
        media_type="application/x-ndjson",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@router.get("/{session_id}/state")
async def get_session_state(session_id: int = Path(...)):
    """
    현재 세션 상태 전체 조회 (디버깅/프론트 초기화용).
    """
    state = await session_store.get(session_id)
    if state is None:
        raise HTTPException(status_code=404, detail=f"세션 {session_id}을 찾을 수 없습니다.")
    return state.model_dump()


@router.delete("/{session_id}")
async def delete_session(session_id: int = Path(...)):
    """세션 삭제."""
    await session_store.delete(session_id)
    return {"ok": True, "session_id": session_id}
