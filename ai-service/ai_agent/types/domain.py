"""
MergeEduAgent 도메인 타입 정의

설계서(통합_교육_에이전트.pdf v1.0) §7 상태 모델 기준
"""
from __future__ import annotations

from enum import Enum
from typing import Any, Dict, List, Literal, Optional
from pydantic import BaseModel, Field


# ---------------------------------------------------------------------------
# 이벤트 타입
# ---------------------------------------------------------------------------

class AppEventType(str, Enum):
    SESSION_ENTERED = "SESSION_ENTERED"
    START_EXPLANATION_DECISION = "START_EXPLANATION_DECISION"
    PAGE_CHANGED = "PAGE_CHANGED"
    USER_MESSAGE = "USER_MESSAGE"
    QUIZ_DECISION = "QUIZ_DECISION"
    QUIZ_TYPE_SELECTED = "QUIZ_TYPE_SELECTED"
    QUIZ_SUBMITTED = "QUIZ_SUBMITTED"
    REVIEW_DECISION = "REVIEW_DECISION"
    RETEST_DECISION = "RETEST_DECISION"
    NEXT_PAGE_DECISION = "NEXT_PAGE_DECISION"
    SAVE_AND_EXIT = "SAVE_AND_EXIT"


class AppEvent(BaseModel):
    """프론트엔드 → 서버로 전달되는 이벤트"""
    type: AppEventType
    payload: Dict[str, Any] = Field(default_factory=dict)

    def get(self, key: str, default: Any = None) -> Any:
        return self.payload.get(key, default)


# ---------------------------------------------------------------------------
# 페이지 상태 머신
# ---------------------------------------------------------------------------

class PageStatus(str, Enum):
    NEW = "NEW"
    EXPLAINING = "EXPLAINING"
    EXPLAINED = "EXPLAINED"
    QUIZ_TYPE_PENDING = "QUIZ_TYPE_PENDING"
    QUIZ_IN_PROGRESS = "QUIZ_IN_PROGRESS"
    QUIZ_GRADED = "QUIZ_GRADED"
    REVIEW_IN_PROGRESS = "REVIEW_IN_PROGRESS"
    DONE = "DONE"


class PageState(BaseModel):
    page_number: int
    chapter_title: Optional[str] = None
    pdf_path: Optional[str] = None
    md_path: Optional[str] = None
    status: PageStatus = PageStatus.NEW
    explanation: Optional[str] = None
    is_key_page: bool = False


# ---------------------------------------------------------------------------
# 퀴즈 기록
# ---------------------------------------------------------------------------

class QuizRecord(BaseModel):
    quiz_id: str
    page_number: int
    quiz_type: str
    questions: List[Dict[str, Any]] = Field(default_factory=list)
    user_answers: List[Dict[str, Any]] = Field(default_factory=list)
    score: Optional[float] = None
    passed: Optional[bool] = None
    graded_at: Optional[str] = None


# ---------------------------------------------------------------------------
# 학습자 모델 (정책 함수 입력에 사용)
# ---------------------------------------------------------------------------

class LearnerModel(BaseModel):
    proficiency_level: Literal["BEGINNER", "INTERMEDIATE", "ADVANCED"] = "INTERMEDIATE"
    recent_scores: List[float] = Field(default_factory=list)
    weak_concepts: List[str] = Field(default_factory=list)
    quiz_attempt_counts: Dict[str, int] = Field(default_factory=dict)

    @property
    def average_recent_score(self) -> float:
        if not self.recent_scores:
            return 1.0
        window = self.recent_scores[-5:]
        return sum(window) / len(window)

    def record_quiz_result(self, page_key: str, score: float, concepts: List[str]) -> None:
        self.recent_scores.append(score)
        self.quiz_attempt_counts[page_key] = self.quiz_attempt_counts.get(page_key, 0) + 1
        if score < 0.6:
            for c in concepts:
                if c not in self.weak_concepts:
                    self.weak_concepts.append(c)


# ---------------------------------------------------------------------------
# 세션 상태
# ---------------------------------------------------------------------------

_MESSAGE_WINDOW = 100  # 최근 메시지만 유지 (Redis 비대화 방지)


class SessionState(BaseModel):
    session_id: int
    lecture_id: int
    current_page: int = 0
    pages: Dict[int, PageState] = Field(default_factory=dict)
    quiz_history: List[QuizRecord] = Field(default_factory=list)
    learner: LearnerModel = Field(default_factory=LearnerModel)
    messages: List[Dict[str, Any]] = Field(default_factory=list)
    waiting_for_answer: bool = False
    current_question_id: Optional[str] = None
    pdf_path: Optional[str] = None
    ai_status_connected: bool = True
    created_at: Optional[str] = None
    updated_at: Optional[str] = None

    def get_current_page_state(self) -> PageState:
        if self.current_page not in self.pages:
            self.pages[self.current_page] = PageState(page_number=self.current_page)
        return self.pages[self.current_page]

    def append_message(self, role: str, content: str, extra: Dict[str, Any] | None = None) -> None:
        msg: Dict[str, Any] = {"role": role, "content": content}
        if extra:
            msg.update(extra)
        self.messages.append(msg)
        # 슬라이딩 윈도우: 최근 N개만 유지
        if len(self.messages) > _MESSAGE_WINDOW:
            self.messages = self.messages[-_MESSAGE_WINDOW:]


# ---------------------------------------------------------------------------
# 오케스트레이터 플랜
# ---------------------------------------------------------------------------

class ActionType(str, Enum):
    SEND_MESSAGE = "SEND_MESSAGE"
    CALL_TOOL = "CALL_TOOL"
    SET_UI_STATE = "SET_UI_STATE"


class ToolName(str, Enum):
    EXPLAIN_PAGE = "EXPLAIN_PAGE"
    ANSWER_QUESTION = "ANSWER_QUESTION"
    GENERATE_QUIZ_FIVE_CHOICE = "GENERATE_QUIZ_FIVE_CHOICE"
    GENERATE_QUIZ_OX = "GENERATE_QUIZ_OX"
    GENERATE_QUIZ_SHORT = "GENERATE_QUIZ_SHORT"
    GENERATE_QUIZ_FLASH = "GENERATE_QUIZ_FLASH"
    AUTO_GRADE_MCQ_OX = "AUTO_GRADE_MCQ_OX"
    GRADE_SHORT_OR_ESSAY = "GRADE_SHORT_OR_ESSAY"
    WRITE_FEEDBACK_ENTRY = "WRITE_FEEDBACK_ENTRY"


class OrchestratorAction(BaseModel):
    type: ActionType
    tool: Optional[ToolName] = None
    params: Dict[str, Any] = Field(default_factory=dict)
    message: Optional[str] = None
    ui_state: Optional[Dict[str, Any]] = None


class OrchestratorPlan(BaseModel):
    actions: List[OrchestratorAction] = Field(default_factory=list)


# ---------------------------------------------------------------------------
# NDJSON 스트리밍 이벤트 (설계서 §9 기준 통일 포맷)
# ---------------------------------------------------------------------------

class NdjsonEventType(str, Enum):
    AGENT_DELTA = "agent_delta"   # 텍스트 스트리밍 (channel: "thought"|"main")
    DONE = "done"
    ERROR = "error"
    HEARTBEAT = "heartbeat"       # 연결 유지용 keep-alive (클라이언트가 무시해도 됨)


class NdjsonEvent(BaseModel):
    type: NdjsonEventType
    agent: Optional[str] = None    # "explainer" | "qa" | "quiz" | "grader" | "system"
    tool: Optional[str] = None     # ToolName 문자열
    channel: Optional[str] = None  # "thought" | "main"
    delta: Optional[str] = None
    data: Optional[Dict[str, Any]] = None
    message: Optional[str] = None
    final: Optional[bool] = None   # done 이벤트에서 True
    # error 이벤트 전용 구조화 필드
    code: Optional[str] = None                      # 에러 코드 (예: QUIZ_PROFILE_VALIDATION_FAILED)
    details: Optional[List[Dict[str, Any]]] = None  # 필드별 상세 오류 목록

    def to_ndjson_line(self) -> str:
        return self.model_dump_json(exclude_none=True) + "\n"
