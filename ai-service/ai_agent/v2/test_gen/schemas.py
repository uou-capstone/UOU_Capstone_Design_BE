"""
LectureTestGenerator Pydantic 스키마 정의
모든 입출력 데이터 구조를 Pydantic 모델로 정의
"""
from pydantic import BaseModel, Field
from typing import List, Optional, Dict, Literal, Union, Any
from enum import Enum


# ===== Common Enums =====
class ProficiencyLevel(str, Enum):
    BEGINNER = "Beginner"
    INTERMEDIATE = "Intermediate"
    ADVANCED = "Advanced"


class ExamType(str, Enum):
    FIVE_CHOICE = "Five_Choice"
    OX_PROBLEM = "OX_Problem"
    FLASH_CARD = "Flash_Card"
    SHORT_ANSWER = "Short_Answer"
    DEBATE = "Debate"


class TargetDepth(str, Enum):
    CONCEPT = "Concept"
    APPLICATION = "Application"
    DERIVATION = "Derivation"
    DEEP_UNDERSTANDING = "Deep Understanding"


class QuestionModality(str, Enum):
    MATHEMATICAL = "Mathematical"
    THEORETICAL = "Theoretical"
    BALANCE = "Balance"


class LanguagePreference(str, Enum):
    KOREAN_WITH_ENGLISH_TERMS = "Korean_with_English_Terms"
    KOREAN_WITH_KOREAN_TERMS = "Korean_with_Korean_Terms"
    ONLY_ENGLISH = "Only_English"


class Strictness(str, Enum):
    STRICT = "Strict"
    LENIENT = "Lenient"
    MODERATE = "Moderate"


class ExplanationDepth(str, Enum):
    ANSWER_ONLY = "Answer_Only"
    DETAILED_WITH_EXAMPLES = "Detailed_with_Examples"
    BRIEF = "Brief"
    DETAILED = "Detailed"


class ScopeBoundary(str, Enum):
    LECTURE_MATERIAL_ONLY = "Lecture_Material_Only"
    ALLOW_EXTERNAL_KNOWLEDGE = "Allow_External_Knowledge"


# ===== Profile Schemas =====
class LearningGoal(BaseModel):
    focus_areas: List[str] = Field(
        default_factory=list,
        description="집중 학습 영역 (핵심 키워드 리스트)"
    )
    target_depth: TargetDepth = Field(
        default=TargetDepth.CONCEPT,
        description="목표 이해 수준"
    )
    question_modality: QuestionModality = Field(
        default=QuestionModality.BALANCE,
        description="문제 출제 스타일"
    )


class UserStatus(BaseModel):
    proficiency_level: ProficiencyLevel = Field(
        default=ProficiencyLevel.INTERMEDIATE,
        description="사용자의 현재 지식 수준"
    )
    weakness_focus: Optional[bool] = Field(
        default=None,
        description="True일 경우, 사용자의 오답 노트나 취약점을 기반으로 문제 생성"
    )


class InteractionStyle(BaseModel):
    language_preference: LanguagePreference = Field(
        default=LanguagePreference.KOREAN_WITH_ENGLISH_TERMS,
        description="용어 및 해설의 언어 표기 방식"
    )
    scenario_based: bool = Field(
        default=False,
        description="True일 경우, 단순 정의보다는 구체적인 상황이나 예시를 부여하여 질문"
    )


class FeedbackPreference(BaseModel):
    strictness: Strictness = Field(
        default=Strictness.MODERATE,
        description="채점 기준의 엄격도"
    )
    explanation_depth: ExplanationDepth = Field(
        default=ExplanationDepth.DETAILED_WITH_EXAMPLES,
        description="해설의 깊이"
    )


class TestProfile(BaseModel):
    """사용자 학습 프로필"""
    learning_goal: LearningGoal
    user_status: UserStatus
    interaction_style: InteractionStyle
    feedback_preference: FeedbackPreference
    scope_boundary: ScopeBoundary = Field(
        default=ScopeBoundary.LECTURE_MATERIAL_ONLY,
        description="지식의 탐색 범위"
    )


# ===== Request/Response Schemas =====
class ProblemRequest(BaseModel):
    """문제 생성 요청"""
    exam_type: ExamType
    target_count: int = Field(
        ge=1,
        le=30,
        default=10,
        description="생성할 문제/카드 수"
    )
    lecture_content: str = Field(
        ...,
        description="강의 자료 텍스트 (Markdown 등) 또는 파일 경로"
    )
    user_profile: Optional[TestProfile] = Field(
        default=None,
        description="없으면 자동 생성"
    )


# ===== Individual Problem Schemas =====
class FiveChoiceOption(BaseModel):
    """5지선다 선택지"""
    id: str = Field(..., pattern="^[1-5]$", description="선택지 번호 (1-5)")
    content: str = Field(..., description="선택지 내용")
    intent: str = Field(..., description="출제 의도 (정답인 이유 또는 오답 유도 논리)")


class FiveChoiceProblem(BaseModel):
    """5지선다 문제"""
    id: int
    question_content: str = Field(..., description="문제 발문")
    options: List[FiveChoiceOption] = Field(..., min_length=5, max_length=5)
    correct_answer: str = Field(..., pattern="^[1-5]$", description="정답 선택지 번호")
    intent_diagnosis: Optional[str] = Field(
        default=None,
        description="문제 전체의 출제 의도 및 학습 진단 가이드"
    )


class FiveChoiceResponse(BaseModel):
    """5지선다 문제 생성 응답"""
    mcq_problems: List[FiveChoiceProblem]


class OXProblem(BaseModel):
    """OX 문제"""
    id: int
    question_content: str = Field(..., description="문제 발문")
    correct_answer: Literal["O", "X"] = Field(..., description="정답")
    explanation: str = Field(..., description="해설")
    intent_type: Optional[str] = Field(
        default=None,
        description="문제 유형 (Fact_Check, Common_Misconception 등)"
    )


class OXResponse(BaseModel):
    """OX 문제 생성 응답"""
    ox_problems: List[OXProblem]


class FlashCard(BaseModel):
    """플래시카드"""
    id: int
    category_tag: Optional[str] = Field(default=None, description="카테고리 태그")
    front_content: str = Field(..., description="앞면 (질문/용어)")
    back_content: str = Field(..., description="뒷면 (답/정의)")
    complexity_level: Optional[str] = Field(default=None, description="난이도")


class FlashCardResponse(BaseModel):
    """플래시카드 생성 응답"""
    flash_cards: List[FlashCard]


class ShortAnswerProblem(BaseModel):
    """단답형 문제"""
    id: int
    question_content: str = Field(..., description="문제 발문")
    related_keywords: List[str] = Field(default_factory=list, description="관련 키워드")
    best_answer: str = Field(..., description="모범 답안")
    evaluation_criteria: str = Field(..., description="평가 기준")


class ShortAnswerResponse(BaseModel):
    """단답형 문제 생성 응답"""
    short_answer_problems: List[ShortAnswerProblem]


# ===== Debate Setup Schema =====
class DebateTopic(BaseModel):
    """토론 주제 설정 (Debate Agent용)"""
    topic: str = Field(..., description="토론 주제")
    context: str = Field(..., description="배경 설명")
    pro_side_stand: str = Field(..., description="찬성 측 입장")
    con_side_stand: str = Field(..., description="반대 측 입장")
    evaluation_criteria: List[str] = Field(default_factory=list, description="평가 기준")


class DebateSetupResponse(BaseModel):
    """토론 주제 생성 응답"""
    topics: List[DebateTopic] = Field(..., description="생성된 토론 주제 리스트")


# ===== Planning Schemas =====
class PlannedItem(BaseModel):
    """계획된 문제 항목 (Planner 출력)"""
    id: int
    target_topic: str = Field(..., description="다룰 핵심 개념")
    intent_type: str = Field(..., description="질문의 의도")
    complexity_level: str = Field(..., description="난이도 (Basic/Intermediate/Advanced)")
    source_reference_hint: str = Field(..., description="강의 자료 내 정답 근거")
    focus_point: Optional[str] = Field(default=None, description="오답 유도 포인트")


class FiveChoicePlan(BaseModel):
    """5지선다 출제 계획"""
    planning_strategy: str = Field(..., description="전체적인 출제 전략")
    planned_items: List[PlannedItem]


class OXPlannedItem(PlannedItem):
    """OX 문제 계획 항목"""
    target_correctness: Literal["O", "X"] = Field(..., description="목표 정답 (O 또는 X)")


class OXPlan(BaseModel):
    """OX 문제 출제 계획"""
    planning_strategy: str = Field(..., description="전체적인 출제 전략")
    planned_items: List[OXPlannedItem]


# ===== Validation Schemas =====
class FeedbackItem(BaseModel):
    """검증 피드백 항목"""
    id: int = Field(..., description="오류가 발견된 문제의 고유 식별 번호")
    message: str = Field(..., description="구체적인 오류 원인 및 수정 가이드")


class ValidationResult(BaseModel):
    """문제 검증 결과"""
    is_valid: bool = Field(..., description="검증 통과 여부")
    reasoning: Optional[str] = Field(default=None, description="검증 이유")
    feedback_message: Optional[List[FeedbackItem]] = Field(
        default=None,
        description="검증 실패 시 반환되는 수정 지침 리스트. 통과 시 빈 배열([]) 반환."
    )


# ===== Profile Generation Schemas =====
class ProfileAnalysisResponse(BaseModel):
    """프로필 분석 응답"""
    status: Literal["INCOMPLETE", "COMPLETE"] = Field(..., description="프로필 완성도")
    missing_info: List[str] = Field(default_factory=list, description="누락된 필드 리스트")
    missing_info_queries: str = Field(..., description="사용자에게 할 질문 또는 완료 메시지")


# ===== Integrated Response =====
class TestGenerationResponse(BaseModel):
    """통합 테스트 생성 응답"""
    exam_type: ExamType
    user_profile: TestProfile
    problems: Union[
        FiveChoiceResponse,
        OXResponse,
        FlashCardResponse,
        ShortAnswerResponse,
        DebateTopic
    ]
    metadata: Dict[str, Any] = Field(default_factory=dict)


# ===== Grading Schemas =====
class UserAnswer(BaseModel):
    """사용자 답안"""
    problem_id: int = Field(..., description="문제 ID")
    user_response: str = Field(..., description="선택한 번호('1') 또는 주관식 답안('텍스트')")


class ProblemFeedback(BaseModel):
    """문제별 피드백"""
    problem_id: int = Field(..., description="문제 ID")
    is_correct: bool = Field(..., description="정답 여부")
    user_response: str = Field(..., description="사용자 답안")
    correct_answer: str = Field(..., description="정답")
    explanation: str = Field(..., description="해설")
    score: float = Field(default=0.0, description="부분 점수 (0.0 ~ 10.0)")


class GradingRequest(BaseModel):
    """채점 요청"""
    exam_type: ExamType = Field(..., description="시험 유형")
    problems: List[Any] = Field(..., description="생성되었던 문제 리스트 (Stateless 서버이므로 다시 받아야 함)")
    user_answers: List[UserAnswer] = Field(..., description="사용자 답안 리스트")
    lecture_content: str = Field(..., description="피드백 생성 시 문맥 참고용 강의 자료")


class GradingResponse(BaseModel):
    """채점 응답"""
    total_score: float = Field(..., description="총점")
    max_score: float = Field(..., description="만점")
    results: List[ProblemFeedback] = Field(..., description="문제별 채점 결과")
    overall_feedback: str = Field(..., description="전체 총평 (AI 생성)")


# ===== Feedback Schemas (기존 호환성 유지) =====
class EvaluationItem(BaseModel):
    """평가 항목 (채점 결과)"""
    problem_id: int
    user_answer: Any
    correct_answer: Any
    is_correct: bool
    score: Optional[float] = None
    feedback: Optional[str] = None


class FeedbackResponse(BaseModel):
    """피드백 응답"""
    session_meta: Dict[str, Any] = Field(..., description="세션 메타데이터")
    evaluation_items: List[EvaluationItem] = Field(..., description="평가 항목 리스트")
