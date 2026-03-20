# ai-service/app/routers/test_gen.py
"""
시험 문제 생성 라우터
LectureTestGenerator를 사용하여 다양한 유형의 시험 문제를 생성합니다.
"""
import hashlib
import json
from fastapi import APIRouter, HTTPException, Depends
from pydantic import BaseModel, Field
from typing import Optional, List, Dict, Any
from redis.asyncio import Redis

from app.core.redis_client import get_redis
from ai_agent.LectureTestGenerator.main import LectureTestGenerator
from ai_agent.LectureTestGenerator.schemas import (
    ProblemRequest,
    TestGenerationResponse,
    TestProfile,
    ExamType,
)
from ai_agent.LectureTestGenerator.profile import (
    generate_profile_async,
    update_profile_async,
    analyze_profile_async,
    get_default_test_profile,
)


router = APIRouter(prefix="/api/test-gen", tags=["Test Generator"])

# 전역 Generator 인스턴스 (싱글톤)
generator: Optional[LectureTestGenerator] = None


def get_generator() -> LectureTestGenerator:
    """
    LectureTestGenerator 인스턴스를 반환합니다.
    싱글톤 패턴으로 한 번만 생성합니다.
    """
    global generator
    if generator is None:
        generator = LectureTestGenerator()
    return generator


# --- [1] Profile Request / Response 스키마 ---
class ProfileContext(BaseModel):
    lecture_content: str
    exam_type: Optional[str] = None      # 프론트에서 이미 선택한 시험 유형
    topic: Optional[str] = None          # 프론트에서 이미 선택한 주제
    problem_count: Optional[int] = None  # 프론트에서 이미 선택한 문제 수
    existing_profile: Optional[Dict[str, Any]] = None
    user_message: Optional[str] = None


class ProfileRequest(BaseModel):
    prompt: str = Field(default="Generate or validate test profile")
    context: ProfileContext


class ProfileResponse(BaseModel):
    status: str  # "INCOMPLETE" or "COMPLETE"
    agent_message: str
    missing_info: List[str] = []
    updated_profile: Dict[str, Any]


@router.post("/profile", response_model=ProfileResponse)
async def chat_and_update_profile(request: ProfileRequest):
    """
    사용자와 대화하며 시험 생성용 프로필을 완성하는 엔드포인트.
    Gemini AI(UpdateAgent -> AnalyzeAgent)가 동작합니다.
    """
    generator_instance = get_generator()
    if not generator_instance:
        raise HTTPException(status_code=503, detail="AI Service Not Initialized")

    ctx = request.context
    current_profile_raw = ctx.existing_profile or {}
    user_msg = (ctx.user_message or "").strip()

    # exam_type이 없으면 에러 반환 (프론트에서 반드시 선택해야 함)
    if not ctx.exam_type:
        raise HTTPException(status_code=400, detail="exam_type은 필수입니다. 프론트에서 시험 유형을 선택해주세요.")
    exam_type_str = ctx.exam_type

    # dict -> TestProfile (빈 객체면 기본 프로필 사용)
    try:
        current_profile_obj = (
            TestProfile.model_validate(current_profile_raw)
            if current_profile_raw
            else get_default_test_profile()
        )
    except Exception:
        current_profile_obj = get_default_test_profile()

    # 프론트에서 확정된 topic을 focus_areas에 pre-populate (없는 경우에만)
    if ctx.topic and not current_profile_obj.learning_goal.focus_areas:
        current_profile_obj.learning_goal.focus_areas = [ctx.topic]

    try:
        # Step 1: 사용자 메시지가 있으면 프로필 업데이트 (Update Agent)
        if user_msg:
            updated_profile_obj = await update_profile_async(
                current_profile=current_profile_obj,
                user_input=user_msg,
                client=generator_instance.client,
            )
        else:
            updated_profile_obj = current_profile_obj

        # Step 2: 업데이트된 프로필 분석 → 다음 질문 또는 COMPLETE (Analyze Agent)
        try:
            exam_type_enum = ExamType(exam_type_str)
        except ValueError:
            exam_type_enum = ExamType.FLASH_CARD
        analysis_result = await analyze_profile_async(
            current_profile=updated_profile_obj,
            exam_type=exam_type_enum,
            client=generator_instance.client,
            topic=ctx.topic,
            problem_count=ctx.problem_count,
        )

        # Step 3: 백엔드 명세에 맞춰 응답 (snake_case -> camelCase는 Spring에서 처리 가능)
        return ProfileResponse(
            status=analysis_result.status,
            agent_message=analysis_result.missing_info_queries,
            missing_info=analysis_result.missing_info,
            updated_profile=updated_profile_obj.model_dump(),
        )
    except Exception as e:
        print(f"[Profile Chat Error] {e}")
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail="프로필 연동 중 오류가 발생했습니다.")


@router.post("/generate", response_model=TestGenerationResponse)
async def generate_test_route(
    request: ProblemRequest,
    redis: Redis = Depends(get_redis)  # [NEW] Redis 주입
):
    """
    시험 문제를 생성합니다.
    
    Redis 캐싱:
    - 프로필이 자동 생성되는 경우, 강의 내용의 해시를 키로 사용하여 캐싱
    - TTL: 24시간 (86400초)
    """
    generator_instance = get_generator()
    
    if not generator_instance:
        raise HTTPException(status_code=503, detail="AI Service Not Initialized")
    
    try:
        # ---------------------------------------------------------
        # Redis Caching Logic for User Profile
        # ---------------------------------------------------------
        profile = request.user_profile
        
        # 프로필이 요청에 없다면 (자동 생성 모드)
        if not profile:
            # 1. 강의 내용 해시 생성 (Cache Key)
            content_hash = hashlib.md5(request.lecture_content.encode('utf-8')).hexdigest()
            cache_key = f"fa:cache:profile:{content_hash}"
            
            # 2. Redis 조회
            try:
                cached_data = await redis.get(cache_key)
                
                if cached_data:
                    # Cache Hit! 🎯
                    print(f"[Redis] Profile Cache Hit: {cache_key}")
                    profile = TestProfile.model_validate_json(cached_data)
                else:
                    # Cache Miss.. AI 생성 🐢
                    print(f"[Redis] Profile Cache Miss. Generating...")
                    profile = await generate_profile_async(
                        request.lecture_content,
                        generator_instance.client
                    )
                    
                    # 3. Redis 저장 (TTL 24시간)
                    await redis.setex(
                        cache_key,
                        86400,  # 24시간
                        profile.model_dump_json()
                    )
                    print(f"[Redis] Profile cached: {cache_key}")
            except Exception as redis_error:
                # Redis 오류 시에도 정상 동작 (Fallback)
                print(f"[Redis] Error: {redis_error}. Falling back to direct generation.")
                profile = await generate_profile_async(
                    request.lecture_content,
                    generator_instance.client
                )
        else:
            print(">> Using provided User Profile.")
        
        # ---------------------------------------------------------
        # 요청 객체에 프로필 주입 후 생성기 호출
        # ---------------------------------------------------------
        request.user_profile = profile
        response = await generator_instance.generate_test(request)
        return response

    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        print(f"[Error] Test generation failed: {e}")
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=f"시험 문제 생성 실패: {str(e)}")


@router.get("/health")
async def health_check(redis: Redis = Depends(get_redis)):
    """
    Redis 연결 상태를 확인합니다.
    """
    try:
        await redis.ping()
        return {
            "status": "ok",
            "redis": "connected"
        }
    except Exception as e:
        return {
            "status": "error",
            "redis": f"disconnected: {str(e)}"
        }
