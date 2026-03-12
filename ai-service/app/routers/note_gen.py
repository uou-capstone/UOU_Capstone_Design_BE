# ai-service/app/routers/note_gen.py
import uuid
import json
from typing import Any, Dict

from fastapi import APIRouter, HTTPException, BackgroundTasks, Depends
from pydantic import BaseModel, Field
from redis.asyncio import Redis

from app.core.redis_client import get_redis
from app.services.note_gen_service import (
    run_phase1,
    run_phase2,
    run_phase3_to_5_task,
)


class Phase1Request(BaseModel):
    topic: str = Field(..., description="생성할 강의 주제")
    audience_level: str = Field(
        default="University Students", description="대상 독자 수준"
    )


class Phase2Request(BaseModel):
    draft_plan: Dict[str, Any] = Field(..., description="Phase 1에서 생성된 기획안")
    user_feedback: str = Field(
        ..., description="사용자 피드백 (수정 요청 또는 코멘트)"
    )


class Phase3Request(BaseModel):
    session_id: int = Field(
        ..., description="백엔드 세션 ID (finalized_brief:{sessionId} 조회용)"
    )


router = APIRouter(
    prefix="/api/lecture-gen",
    tags=["Lecture Note Generator"],
)


@router.post("/phase1/planning")
async def phase1_planning_endpoint(req: Phase1Request):
    """
    Phase 1: 기획안 생성 (Draft Plan)
    """
    try:
        draft_plan = await run_phase1(req.topic, req.audience_level)
        return {"draft_plan": draft_plan}
    except Exception as e:
        raise HTTPException(
            status_code=500, detail=f"Phase 1 기획안 생성 실패: {str(e)}"
        )


@router.post("/phase2/update")
async def phase2_update_endpoint(req: Phase2Request):
    """
    Phase 2: 피드백 반영 및 목차/브리프 확정
    """
    try:
        finalized_brief = await run_phase2(req.draft_plan, req.user_feedback)
        return {"finalized_brief": finalized_brief}
    except Exception as e:
        raise HTTPException(
            status_code=500, detail=f"Phase 2 업데이트 실패: {str(e)}"
        )


@router.post("/phase3-5/auto")
async def phase3_to_5_auto_endpoint(
    req: Phase3Request,
    background_tasks: BackgroundTasks,
):
    """
    Phase 3~5: 집필/검토/조립 비동기 실행
    - 백엔드는 finalized_brief를 미리 Redis에 저장하고, 여기에는 sessionId만 전달합니다.
    - 진행 상황은 Redis Pub/Sub 채널(progress:session:{sessionId})로 방송됩니다.
    """
    session_id: int = req.session_id

    try:
        background_tasks.add_task(
            run_phase3_to_5_task,
            session_id,
        )

        return {
            "session_id": session_id,
            "status": "accepted",
            "message": "Phase 3~5 강의 노트 생성이 시작되었습니다.",
            "progress_channel": f"progress:session:{session_id}",
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Phase 3~5 작업 등록 실패: {str(e)}")


@router.get("/status/{task_id}")
async def get_task_status(
    task_id: str,
    redis: Redis = Depends(get_redis),
):
    """
    Task 진행 상태 조회

    Returns:
        - status: queued | processing | completed | failed
        - progress: 0-100 (진행률)
        - message: 상태 메시지
        - result: 완료 시 생성된 마크다운 텍스트 (status가 completed일 때만)
        - error: 실패 시 에러 메시지 (status가 failed일 때만)
    """
    try:
        data = await redis.get(f"task:{task_id}")
        if not data:
            raise HTTPException(status_code=404, detail="Task not found")

        status_data = json.loads(data)
        return status_data
    except json.JSONDecodeError as e:
        raise HTTPException(status_code=500, detail=f"상태 데이터 파싱 실패: {str(e)}")
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"상태 조회 실패: {str(e)}")
