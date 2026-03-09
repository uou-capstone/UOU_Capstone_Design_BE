"""
사용자 프로필 생성 및 관리 로직
기존 Prior_Profile_Gen_Agent.py를 비동기로 리팩토링
"""
import json
import asyncio
from typing import Optional
from google import genai
from google.genai import types
from .schemas import TestProfile, ProfileAnalysisResponse, ExamType
from .prompts import (
    PROFILE_GENERATION_SYSTEM_PROMPT,
    PROFILE_ANALYSIS_SYSTEM_PROMPT,
    UPDATE_PROFILE_SYSTEM_PROMPT
)
from .utils import get_gemini_client


async def generate_profile_async(
    lecture_content: str,
    client: Optional[genai.Client] = None,
    exam_type: Optional[ExamType] = None
) -> TestProfile:
    """
    강의 내용을 분석하여 학습자 프로필(TestProfile)을 자동 생성합니다.
    
    Args:
        lecture_content: 강의 자료 텍스트
        client: Gemini Client (없으면 자동 생성)
        exam_type: 시험 유형 (선택사항, 향후 확장용)
    
    Returns:
        TestProfile: 생성된 프로필
    """
    if client is None:
        client = get_gemini_client()
    
    print("[Profile] Generating user profile from lecture content...")
    
    # 강의 내용이 너무 길면 자름
    lecture_truncated = lecture_content[:20000] if len(lecture_content) > 20000 else lecture_content
    
    # 프롬프트 구성
    contents = [
        f"[Lecture Content Excerpt]\n{lecture_truncated}"
    ]
    
    try:
        # 비동기 호출
        response = await asyncio.to_thread(
            client.models.generate_content,
            model="gemini-2.5-flash",
            contents=contents,
            config=types.GenerateContentConfig(
                system_instruction=PROFILE_GENERATION_SYSTEM_PROMPT,
                response_mime_type="application/json",
                response_schema=TestProfile.model_json_schema()
            )
        )
        
        # JSON 파싱 및 객체 생성
        profile_data = json.loads(response.text)
        profile = TestProfile(**profile_data)
        print("[Profile] Profile generated successfully.")
        return profile
        
    except Exception as e:
        print(f"[Profile] Error generating profile: {e}")
        import traceback
        traceback.print_exc()
        
        # 실패 시 기본 프로필 반환 (Fallback)
        print("[Profile] Using default profile as fallback.")
        from .schemas import (
            LearningGoal, UserStatus, InteractionStyle, FeedbackPreference,
            ScopeBoundary, ProficiencyLevel, TargetDepth, QuestionModality,
            LanguagePreference, Strictness, ExplanationDepth
        )
        
        return get_default_test_profile()


def get_default_test_profile() -> TestProfile:
    """빈/초기 프로필용 기본 TestProfile (프로필 대화 첫 턴 등)."""
    from .schemas import (
        LearningGoal,
        UserStatus,
        InteractionStyle,
        FeedbackPreference,
        ScopeBoundary,
        TargetDepth,
        QuestionModality,
        ProficiencyLevel,
        LanguagePreference,
        Strictness,
        ExplanationDepth,
    )
    return TestProfile(
        learning_goal=LearningGoal(
            focus_areas=[],
            target_depth=TargetDepth.CONCEPT,
            question_modality=QuestionModality.BALANCE,
        ),
        user_status=UserStatus(
            proficiency_level=ProficiencyLevel.INTERMEDIATE,
            weakness_focus=False,
        ),
        interaction_style=InteractionStyle(
            language_preference=LanguagePreference.KOREAN_WITH_ENGLISH_TERMS,
            scenario_based=False,
        ),
        feedback_preference=FeedbackPreference(
            strictness=Strictness.MODERATE,
            explanation_depth=ExplanationDepth.DETAILED_WITH_EXAMPLES,
        ),
        scope_boundary=ScopeBoundary.LECTURE_MATERIAL_ONLY,
    )


async def analyze_profile_async(
    current_profile: TestProfile,
    exam_type: ExamType,
    client: Optional[genai.Client] = None
) -> ProfileAnalysisResponse:
    """
    현재 프로필의 완성도 분석 (비동기)
    
    Args:
        current_profile: 현재 프로필
        exam_type: 시험 유형
        client: Gemini Client
    
    Returns:
        ProfileAnalysisResponse: 분석 결과
    """
    if client is None:
        client = get_gemini_client()
    
    # TODO: 실제 구현 시 AI가 프로필 완성도 분석
    # 현재는 기본 응답 반환
    return ProfileAnalysisResponse(
        status="COMPLETE",
        missing_info=[],
        missing_info_queries="프로필이 완성되었습니다."
    )


async def update_profile_async(
    current_profile: TestProfile,
    user_input: str,
    client: Optional[genai.Client] = None
) -> TestProfile:
    """
    사용자 입력을 바탕으로 프로필 업데이트 (비동기)
    
    Args:
        current_profile: 현재 프로필
        user_input: 사용자 입력 텍스트
        client: Gemini Client
    
    Returns:
        TestProfile: 업데이트된 프로필
    """
    if client is None:
        client = get_gemini_client()
    
    # TODO: 실제 구현 시 AI가 사용자 입력을 분석하여 프로필 업데이트
    # 현재는 원본 반환 (추후 구현)
    return current_profile
