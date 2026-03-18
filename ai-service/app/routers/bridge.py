"""
Bridge Router

설계서 Option B 절충안:
Spring Boot가 단건 태스크를 FastAPI에 위임하는 엔드포인트.

허용 패턴:  Spring Boot → FastAPI 단건 요청 → 결과 수령
금지 패턴:  Spring Boot가 /bridge/* 를 순서대로 여러 번 호출해 오케스트레이션 수행

엔드포인트:
  POST /bridge/quiz   → 퀴즈 생성 (단건 태스크 위임)
  POST /bridge/grade  → 채점     (단건 태스크 위임)

세션 기반 학습 흐름(설명 → Q&A → 퀴즈 → 채점)은
반드시 /api/session/{id}/event/stream 을 사용해야 합니다.
"""
from __future__ import annotations

from typing import Any, Dict, List, Optional

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from ai_agent.agents.GraderAgent import GraderAgent
from ai_agent.agents.QuizAgents import QuizAgents
from ai_agent.bridge.GeminiBridgeClient import GeminiBridgeClient
from ai_agent.types.domain import NdjsonEvent, NdjsonEventType

router = APIRouter(prefix="/bridge", tags=["bridge"])

_bridge = GeminiBridgeClient()
_quiz = QuizAgents(_bridge)
_grader = GraderAgent(_bridge)

_NDJSON_HEADERS = {
    "Cache-Control": "no-cache",
    "Connection": "keep-alive",
    "X-Accel-Buffering": "no",
}


# ---------------------------------------------------------------------------
# 요청 스키마
# ---------------------------------------------------------------------------

class QuizRequest(BaseModel):
    quiz_type: str = Field(
        default="Five_Choice",
        description="Five_Choice | OX_Problem | Flash_Card | Short_Answer | Debate",
    )
    lecture_content: str
    count: int = Field(default=5, ge=1, le=20)
    profile: Optional[Dict[str, Any]] = None


class GradeRequest(BaseModel):
    quiz_type: str = Field(description="Five_Choice | OX_Problem | Short_Answer | Debate")
    problems: List[Dict[str, Any]]
    user_answers: List[Any]
    lecture_content: str = ""


# ---------------------------------------------------------------------------
# 엔드포인트
# ---------------------------------------------------------------------------

@router.post("/quiz")
async def bridge_quiz(req: QuizRequest):
    """
    퀴즈 생성 단건 태스크.

    Spring Boot가 "이 강의 내용으로 퀴즈 N개 만들어줘"라고 위임하는 용도.
    완료 시 done.data.quiz 에 문제 배열이 포함됩니다.

    ⚠ 이 엔드포인트는 단건 요청 전용입니다.
      학습 세션 흐름에서의 퀴즈 생성은 /api/session/{id}/event/stream 을 사용하세요.
    """
    async def _gen():
        try:
            async for event in _quiz.run_stream(
                req.quiz_type, req.lecture_content, req.profile, req.count
            ):
                yield event.to_ndjson_line()
        except Exception as exc:
            err = NdjsonEvent(type=NdjsonEventType.ERROR, agent="quiz", message=str(exc))
            yield err.to_ndjson_line()

    return StreamingResponse(_gen(), media_type="application/x-ndjson", headers=_NDJSON_HEADERS)


@router.post("/grade")
async def bridge_grade(req: GradeRequest):
    """
    채점 단건 태스크.

    Spring Boot가 "이 답안 채점해줘"라고 위임하는 용도.
    MCQ/OX는 서버 내부 채점, 단답/서술은 LLM 채점.
    완료 시 done.data.grading 에 채점 결과가 포함됩니다.

    ⚠ 이 엔드포인트는 단건 요청 전용입니다.
      학습 세션 흐름에서의 채점은 /api/session/{id}/event/stream 을 사용하세요.
    """
    async def _gen():
        try:
            async for event in _grader.run_stream(
                req.quiz_type, req.problems, req.user_answers, req.lecture_content
            ):
                yield event.to_ndjson_line()
        except Exception as exc:
            err = NdjsonEvent(type=NdjsonEventType.ERROR, agent="grader", message=str(exc))
            yield err.to_ndjson_line()

    return StreamingResponse(_gen(), media_type="application/x-ndjson", headers=_NDJSON_HEADERS)
