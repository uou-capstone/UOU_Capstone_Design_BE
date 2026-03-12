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
        return get_default_test_profile()


def get_default_test_profile() -> TestProfile:
    """빈/초기 프로필용 기본 TestProfile (프로필 대화 첫 턴 등)."""
    from .schemas import (
        LearningGoal, UserStatus, InteractionStyle, FeedbackPreference,
        ScopeBoundary, TargetDepth, QuestionModality, ProficiencyLevel,
        LanguagePreference, Strictness, ExplanationDepth,
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


# 🌟 [TODO 해결] 원본 Profile_Agent.py 로직 완벽 이식
async def analyze_profile_async(
    current_profile: TestProfile,
    exam_type: ExamType,
    client: Optional[genai.Client] = None
) -> ProfileAnalysisResponse:
    """
    현재 프로필의 완성도 분석 (비동기)
    """
    if client is None:
        client = get_gemini_client()
    
    print(f"[Profile] Analyzing profile completeness for exam type: {exam_type.value}...")

    # 원본 코드의 "Current Profile: ...", "Exam Type: ..." 형태 유지
    contents = [
        f"Current Profile: {current_profile.model_dump_json()}",
        f"Exam Type: {exam_type.value}"
    ]

    try:
        # 비동기로 Gemini 호출 (원본의 generate_content 대응)
        response = await asyncio.to_thread(
            client.models.generate_content,
            model="gemini-2.5-flash",  # 최신 API에 맞춰 2.5-flash로 통일
            contents=contents,
            config=types.GenerateContentConfig(
                system_instruction=PROFILE_ANALYSIS_SYSTEM_PROMPT,
                response_mime_type="application/json",
                # 원본 json 스키마 파일 대신 Pydantic 객체의 스키마를 동적으로 생성하여 주입
                response_schema=ProfileAnalysisResponse.model_json_schema() 
            )
        )
        
        analysis_data = json.loads(response.text)
        return ProfileAnalysisResponse(**analysis_data)

    except Exception as e:
        print(f"[Profile] Error analyzing profile: {e}")
        import traceback
        traceback.print_exc()
        # 오류 발생 시 안전하게 COMPLETE 반환하여 서비스가 멈추지 않게 방어
        return ProfileAnalysisResponse(
            status="COMPLETE",
            missing_info=[],
            missing_info_queries="프로필 분석 중 오류가 발생했습니다. 현재 설정으로 진행합니다."
        )


# 🌟 [TODO 해결] 원본 Update_Profile_Logic.py 로직 완벽 이식
async def update_profile_async(
    current_profile: TestProfile,
    user_input: str,
    client: Optional[genai.Client] = None
) -> TestProfile:
    """
    사용자 입력을 바탕으로 프로필 업데이트 (비동기)
    """
    if client is None:
        client = get_gemini_client()
    
    print(f"[Profile] Updating profile based on user input: '{user_input}'...")

    # 원본 코드의 "Current Profile: ...", "User Answer: ..." 형태 유지
    contents = [
        f"Current Profile: {current_profile.model_dump_json()}",
        f"User Answer: {user_input}"
    ]

    try:
        # 비동기로 Gemini 호출
        response = await asyncio.to_thread(
            client.models.generate_content,
            model="gemini-2.5-flash",
            contents=contents,
            config=types.GenerateContentConfig(
                system_instruction=UPDATE_PROFILE_SYSTEM_PROMPT,
                response_mime_type="application/json",
                # 원본 json 스키마 파일 대신 Pydantic 스키마 주입
                response_schema=TestProfile.model_json_schema()
            )
        )
        
        updated_data = json.loads(response.text)
        return TestProfile(**updated_data)

    except Exception as e:
        print(f"[Profile] Error updating profile: {e}")
        import traceback
        traceback.print_exc()
        # 오류 발생 시 원본 프로필 반환 (방어 로직)
        return current_profile