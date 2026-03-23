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

from ai_agent.agents.ExplainerAgent import ExplainerAgent
from ai_agent.bridge.GeminiBridgeClient import GeminiBridgeClient
from ai_agent.types.domain import NdjsonEvent, NdjsonEventType

router = APIRouter(prefix="/api/v2/lectures", tags=["[v2] Lecture"])

_bridge = GeminiBridgeClient()
_explainer = ExplainerAgent(_bridge)

_NDJSON_HEADERS = {
    "Cache-Control": "no-cache",
    "Connection": "keep-alive",
    "X-Accel-Buffering": "no",
}


class LectureGenerateRequest(BaseModel):
    chapter_title: str = Field(..., description="챕터 제목")
    pdf_path: str = Field(..., description="강의 PDF 파일 경로")
    md_path: Optional[str] = Field(default=None, description="강의 대본 MD 파일 경로 (선택)")
    detail: str = Field(default="NORMAL", description="설명 수준: NORMAL | DETAILED")


@router.post("/generate")
async def generate(req: LectureGenerateRequest):
    """
    비스트리밍 강의 설명 생성.
    전체 텍스트를 JSON으로 반환합니다.
    """
    content = await _explainer.run(
        chapter_title=req.chapter_title,
        pdf_path=req.pdf_path,
        md_path=req.md_path,
        detail=req.detail,
    )
    return {"chapter_title": req.chapter_title, "content": content}


@router.post("/generate-stream")
async def generate_stream(req: LectureGenerateRequest):
    """
    NDJSON 스트리밍 강의 설명 생성.

    이벤트 포맷 (agent_delta 규격):
      {"type": "agent_delta", "agent": "explainer", "tool": "EXPLAIN_PAGE", "channel": "thought", "delta": "..."}
      {"type": "agent_delta", "agent": "explainer", "tool": "EXPLAIN_PAGE", "channel": "main",    "delta": "..."}
      {"type": "done",        "agent": "explainer", "tool": "EXPLAIN_PAGE", "final": true, "data": {...}}
      {"type": "error",       "agent": "explainer", "message": "..."}
      {"type": "heartbeat"}
    """
    async def _gen():
        try:
            async for event in _explainer.run_stream(
                chapter_title=req.chapter_title,
                pdf_path=req.pdf_path,
                md_path=req.md_path,
                detail=req.detail,
            ):
                yield event.to_ndjson_line()
        except Exception as exc:
            err = NdjsonEvent(type=NdjsonEventType.ERROR, agent="explainer", message=str(exc))
            yield err.to_ndjson_line()

    return StreamingResponse(_gen(), media_type="application/x-ndjson", headers=_NDJSON_HEADERS)
