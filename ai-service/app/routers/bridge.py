"""
Bridge Router

설계서 Option B 절충안:
Spring Boot가 단건 태스크를 FastAPI에 위임하는 엔드포인트.

허용 패턴:  Spring Boot → FastAPI 단건 요청 → 결과 수령
금지 패턴:  Spring Boot가 /api/v3/bridge/* 를 순서대로 여러 번 호출해 오케스트레이션 수행

엔드포인트:
  POST /api/v3/bridge/quiz         → 퀴즈 생성 (단건 태스크 위임, 스트리밍)
  POST /api/v3/bridge/grade        → 채점     (단건 태스크 위임, 스트리밍)
  POST /api/v3/bridge/quiz/result  → 퀴즈 생성 (비스트리밍 JSON)
  POST /api/v3/bridge/grade/result → 채점     (비스트리밍 JSON)

세션 기반 학습 흐름(설명 → Q&A → 퀴즈 → 채점)은
반드시 /api/v3/session/{id}/event/stream 을 사용해야 합니다.
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
from ai_agent.LectureTestGenerator.schemas import UserAnswer

router = APIRouter(prefix="/api/v3/bridge", tags=["[v3] Bridge"])

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
    """
    퀴즈 생성 요청.
    schemas.py의 ProblemRequest와 필드명을 통일합니다.
    """
    exam_type: str = Field(
        default="Five_Choice",
        description="Five_Choice | OX_Problem | Flash_Card | Short_Answer | Debate",
    )
    lecture_content: str = Field(..., description="강의 자료 텍스트")
    target_count: int = Field(default=5, ge=1, le=20, description="생성할 문제 수")
    user_profile: Optional[Dict[str, Any]] = Field(
        default=None,
        description="TestProfile 객체. 없으면 기본 프로필 사용",
    )


class GradeRequest(BaseModel):
    """
    채점 요청.
    problems는 퀴즈 생성 응답의 문제 배열을 그대로 전달합니다.
    user_answers는 problem_id 기준으로 매핑됩니다.
    """
    exam_type: str = Field(description="Five_Choice | OX_Problem | Short_Answer | Debate")
    problems: List[Dict[str, Any]] = Field(
        ...,
        description="퀴즈 생성 응답(done.data.quiz)에서 받은 문제 배열 그대로 전달",
    )
    user_answers: List[UserAnswer] = Field(
        ...,
        description="[{problem_id: 1, user_response: '2'}, ...] 형식",
    )
    lecture_content: str = Field(default="", description="단답/서술 채점 시 참고할 강의 자료")


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
      학습 세션 흐름에서의 퀴즈 생성은 /api/v3/session/{id}/event/stream 을 사용하세요.
    """
    async def _gen():
        try:
            async for event in _quiz.run_stream(
                req.exam_type, req.lecture_content, req.user_profile, req.target_count
            ):
                yield event.to_ndjson_line()
        except Exception as exc:
            err = NdjsonEvent(type=NdjsonEventType.ERROR, agent="quiz", message=str(exc))
            yield err.to_ndjson_line()

    return StreamingResponse(_gen(), media_type="application/x-ndjson", headers=_NDJSON_HEADERS)


@router.post("/quiz/result")
async def bridge_quiz_result(req: QuizRequest):
    """
    퀴즈 생성 단건 태스크 — 비스트리밍 버전.

    Spring Boot 서버 저장 로직 단순화용.
    스트리밍 UI는 /api/v3/bridge/quiz, 결과 저장은 이 엔드포인트로 분리 가능.

    응답:
    {
      "quiz": [...],
      "quiz_type": "Five_Choice"
    }
    """
    try:
        quiz_data = await _quiz.run(
            req.exam_type, req.lecture_content, req.user_profile, req.target_count
        )
        result = quiz_data if isinstance(quiz_data, dict) else (
            quiz_data.model_dump() if hasattr(quiz_data, "model_dump") else quiz_data
        )
        return {"quiz": result, "quiz_type": req.exam_type}
    except Exception as exc:
        from fastapi import HTTPException
        raise HTTPException(status_code=500, detail=f"퀴즈 생성 실패: {exc}")


@router.post("/grade/result")
async def bridge_grade_result(req: GradeRequest):
    """
    채점 단건 태스크 — 비스트리밍 버전.

    Spring Boot 서버 저장 로직 단순화용.
    스트리밍 UI는 /api/v3/bridge/grade, 결과 저장은 이 엔드포인트로 분리 가능.

    응답:
    {
      "grading": { "results": [...], "total_score": 0.8, "overall_feedback": "..." },
      "passed": true
    }
    """
    try:
        answers_raw = [
            {"index": i, "answer": a.user_response}
            for i, a in enumerate(req.user_answers)
        ]
        result = await _grader.run(
            req.exam_type, req.problems, answers_raw, req.lecture_content
        )
        from ai_agent.agents.GraderAgent import PASS_SCORE_RATIO
        return {
            "grading": result,
            "passed": result.get("total_score", 0) >= PASS_SCORE_RATIO,
        }
    except Exception as exc:
        from fastapi import HTTPException
        raise HTTPException(status_code=500, detail=f"채점 실패: {exc}")


@router.post("/grade")
async def bridge_grade(req: GradeRequest):
    """
    채점 단건 태스크.

    Spring Boot가 "이 답안 채점해줘"라고 위임하는 용도.
    MCQ/OX는 서버 내부 채점, 단답/서술은 LLM 채점.
    완료 시 done.data.grading 에 채점 결과가 포함됩니다.

    ⚠ 이 엔드포인트는 단건 요청 전용입니다.
      학습 세션 흐름에서의 채점은 /api/v3/session/{id}/event/stream 을 사용하세요.
    """
    async def _gen():
        try:
            answers_raw = [
                {"index": i, "answer": a.user_response}
                for i, a in enumerate(req.user_answers)
            ]
            async for event in _grader.run_stream(
                req.exam_type, req.problems, answers_raw, req.lecture_content
            ):
                yield event.to_ndjson_line()
        except Exception as exc:
            err = NdjsonEvent(type=NdjsonEventType.ERROR, agent="grader", message=str(exc))
            yield err.to_ndjson_line()

    return StreamingResponse(_gen(), media_type="application/x-ndjson", headers=_NDJSON_HEADERS)
