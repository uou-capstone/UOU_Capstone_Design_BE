"""
Lecture Router  [v2 Classic Track]

개별 강의 설명 생성 엔드포인트.
세션 없이 단건 호출 방식으로 동작합니다.

엔드포인트:
  POST /api/v2/lectures/generate         → 비스트리밍 (전체 텍스트 반환)
  POST /api/v2/lectures/generate-stream  → NDJSON 스트리밍 (ExplainerAgent)
"""
from __future__ import annotations

from typing import Optional

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from ai_agent.v3.agents.ExplainerAgent import ExplainerAgent
from ai_agent.bridge.GeminiBridgeClient import GeminiBridgeClient
from ai_agent.types.domain import NdjsonEvent, NdjsonEventType
from app.core.path_validator import validate_pdf_path

router = APIRouter(prefix="/api/v2/lectures", tags=["[v2] Lecture"])

_bridge = GeminiBridgeClient()
_explainer = ExplainerAgent(_bridge)

_NDJSON_HEADERS = {
    "Cache-Control": "no-cache",
    "Connection": "keep-alive",
    "X-Accel-Buffering": "no",
}


class LectureGenerateRequest(BaseModel):
    page_number: int = Field(..., ge=1, description="현재 사용자가 보고 있는 페이지 번호 (1부터 시작)")
    pdf_path: str = Field(..., description="강의 PDF 파일 경로")
    chapter_title: Optional[str] = Field(default=None, description="챕터 제목 (없으면 '페이지 N'으로 자동 설정)")
    detail: str = Field(default="NORMAL", description="설명 수준: NORMAL | DETAILED")


@router.post("/generate")
async def generate(req: LectureGenerateRequest):
    """
    비스트리밍 강의 설명 생성.
    전체 텍스트를 JSON으로 반환합니다.
    """
    safe_path = validate_pdf_path(req.pdf_path)
    content = await _explainer.run(
        page_number=req.page_number,
        pdf_path=safe_path,
        chapter_title=req.chapter_title,
        detail=req.detail,
    )
    return {"page_number": req.page_number, "chapter_title": req.chapter_title, "content": content}


@router.post("/generate-stream")
async def generate_stream(req: LectureGenerateRequest):
    """
    NDJSON 스트리밍 강의 설명 생성 (페이지 단위).

    이벤트 포맷 (agent_delta 규격):
      {"type": "agent_delta", "agent": "explainer", "tool": "EXPLAIN_PAGE", "channel": "thought", "delta": "..."}
      {"type": "agent_delta", "agent": "explainer", "tool": "EXPLAIN_PAGE", "channel": "main",    "delta": "..."}
      {"type": "done",        "agent": "explainer", "tool": "EXPLAIN_PAGE", "final": true, "data": {}}
      {"type": "error",       "agent": "explainer", "message": "..."}
      {"type": "heartbeat"}
    """
    safe_path = validate_pdf_path(req.pdf_path)

    async def _gen():
        try:
            async for event in _explainer.run_stream(
                page_number=req.page_number,
                pdf_path=safe_path,
                chapter_title=req.chapter_title,
                detail=req.detail,
            ):
                yield event.to_ndjson_line()
        except Exception as exc:
            err = NdjsonEvent(type=NdjsonEventType.ERROR, agent="explainer", message=str(exc))
            yield err.to_ndjson_line()

    return StreamingResponse(_gen(), media_type="application/x-ndjson", headers=_NDJSON_HEADERS)
