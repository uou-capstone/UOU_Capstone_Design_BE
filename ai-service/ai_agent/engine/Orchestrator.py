"""
Orchestrator

설계서 §4: 규칙 기반 플래너.
입력 이벤트(AppEvent)와 현재 상태를 바탕으로 OrchestratorPlan을 생성한다.
실제 AI 호출은 하지 않고 도구 호출(CALL_TOOL)만 선언한다.
"""
from __future__ import annotations

from typing import List, Optional

from ai_agent.types.domain import (
    ActionType,
    AppEvent,
    AppEventType,
    LearnerModel,
    OrchestratorAction,
    OrchestratorPlan,
    PageState,
    PageStatus,
    SessionState,
    ToolName,
)


class Orchestrator:
    """
    이벤트-분기 규칙 기반 계획 수립기.

    설계서 §4.4 이벤트-분기 요약 구현:
    SESSION_ENTERED / START_EXPLANATION_DECISION / PAGE_CHANGED /
    USER_MESSAGE / QUIZ_DECISION / QUIZ_TYPE_SELECTED / QUIZ_SUBMITTED /
    REVIEW_DECISION / RETEST_DECISION / SAVE_AND_EXIT
    """

    def run(
        self,
        event: AppEvent,
        state: SessionState,
        llm_hint: Optional[dict] = None,
    ) -> OrchestratorPlan:
        """
        이벤트를 받아 OrchestratorPlan(액션 목록)을 생성한다.

        Args:
            event: 클라이언트가 보낸 AppEvent
            state: 현재 세션 상태
            llm_hint: 오케스트레이터 LLM 힌트 (offerQuiz, detailLevel, reason)
        """
        actions: List[OrchestratorAction] = []

        match event.type:
            case AppEventType.SESSION_ENTERED:
                actions = self._handle_session_entered(state)

            case AppEventType.START_EXPLANATION_DECISION:
                if event.get("accept"):
                    actions = self._handle_start_explanation(state, llm_hint)

            case AppEventType.PAGE_CHANGED:
                actions = self._handle_page_changed(state, llm_hint)

            case AppEventType.USER_MESSAGE:
                actions = self._handle_user_message(event, state, llm_hint)

            case AppEventType.QUIZ_DECISION:
                if event.get("accept"):
                    actions = [self._set_ui({"modal": "QUIZ_TYPE_PICKER"})]
                else:
                    actions = self._handle_page_changed(state, llm_hint)

            case AppEventType.QUIZ_TYPE_SELECTED:
                # 프론트에서 명시적으로 선택한 경우 그대로 사용,
                # 없으면 학습자 레벨 기반 추천 타입 사용
                quiz_type = event.get("quizType") or self._recommend_quiz_type(state.learner)
                actions = [self._call_generate_quiz(quiz_type, state)]

            case AppEventType.QUIZ_SUBMITTED:
                quiz_type = event.get("quizType", "Five_Choice")
                actions = self._handle_quiz_submitted(quiz_type, state)

            case AppEventType.REVIEW_DECISION:
                if event.get("accept"):
                    actions = self._handle_review_decision(state)

            case AppEventType.RETEST_DECISION:
                if event.get("accept"):
                    actions = [self._set_ui({"modal": "QUIZ_TYPE_PICKER"})]

            case AppEventType.NEXT_PAGE_DECISION:
                if event.get("accept"):
                    next_page = state.current_page + 1
                    actions = [
                        OrchestratorAction(
                            type=ActionType.SET_UI_STATE,
                            ui_state={"page": next_page},
                        )
                    ]

            case AppEventType.SAVE_AND_EXIT:
                actions = [
                    OrchestratorAction(
                        type=ActionType.SEND_MESSAGE,
                        message="학습 세션이 저장되었습니다. 수고하셨습니다!",
                    )
                ]

        return OrchestratorPlan(actions=actions)

    # ------------------------------------------------------------------
    # 이벤트별 액션 생성 헬퍼
    # ------------------------------------------------------------------

    def _handle_session_entered(self, state: SessionState) -> List[OrchestratorAction]:
        return [
            OrchestratorAction(
                type=ActionType.SEND_MESSAGE,
                message="학습 세션에 오신 것을 환영합니다! 강의를 시작할까요?",
                ui_state={"widget": "START_EXPLANATION_DECISION"},
            )
        ]

    def _handle_start_explanation(
        self, state: SessionState, llm_hint: Optional[dict]
    ) -> List[OrchestratorAction]:
        detail = self._decide_detail_level(state.get_current_page_state(), state.learner, llm_hint)
        actions = [
            OrchestratorAction(
                type=ActionType.CALL_TOOL,
                tool=ToolName.EXPLAIN_PAGE,
                params={"detail": detail},
            )
        ]
        if self._should_offer_quiz(state.get_current_page_state(), state.learner, llm_hint):
            actions.append(
                OrchestratorAction(
                    type=ActionType.SEND_MESSAGE,
                    message="설명이 끝났습니다. 이해를 확인하는 퀴즈를 풀어볼까요?",
                    ui_state={"widget": "QUIZ_DECISION"},
                )
            )
        return actions

    def _handle_page_changed(
        self, state: SessionState, llm_hint: Optional[dict]
    ) -> List[OrchestratorAction]:
        detail = self._decide_detail_level(state.get_current_page_state(), state.learner, llm_hint)
        actions = [
            OrchestratorAction(
                type=ActionType.CALL_TOOL,
                tool=ToolName.EXPLAIN_PAGE,
                params={"detail": detail},
            )
        ]
        if self._should_offer_quiz(state.get_current_page_state(), state.learner, llm_hint):
            actions.append(
                OrchestratorAction(
                    type=ActionType.SEND_MESSAGE,
                    message="이 챕터에 대한 퀴즈를 풀어볼까요?",
                    ui_state={"widget": "QUIZ_DECISION"},
                )
            )
        else:
            actions.append(
                OrchestratorAction(
                    type=ActionType.SEND_MESSAGE,
                    message="다음 페이지로 넘어갈까요?",
                    ui_state={"widget": "NEXT_PAGE_DECISION"},
                )
            )
        return actions

    def _handle_user_message(
        self, event: AppEvent, state: SessionState, llm_hint: Optional[dict]
    ) -> List[OrchestratorAction]:
        text = (event.get("text") or "").lower()
        quiz_keywords = ["퀴즈", "문제", "시험", "quiz", "test"]
        next_keywords = ["다음", "next", "넘어가", "계속"]

        if any(k in text for k in quiz_keywords):
            return [self._set_ui({"modal": "QUIZ_TYPE_PICKER"})]

        if any(k in text for k in next_keywords):
            return self._handle_page_changed(state, llm_hint)

        return [
            OrchestratorAction(
                type=ActionType.CALL_TOOL,
                tool=ToolName.ANSWER_QUESTION,
                params={"question": event.get("text", "")},
            ),
            OrchestratorAction(
                type=ActionType.SEND_MESSAGE,
                message="추가 질문이 있으신가요?",
                ui_state={"widget": "NEXT_PAGE_DECISION"},
            ),
        ]

    def _handle_quiz_submitted(
        self, quiz_type: str, state: SessionState
    ) -> List[OrchestratorAction]:
        if quiz_type in ("Five_Choice", "OX_Problem"):
            tool = ToolName.AUTO_GRADE_MCQ_OX
        else:
            tool = ToolName.GRADE_SHORT_OR_ESSAY

        return [
            OrchestratorAction(
                type=ActionType.CALL_TOOL,
                tool=tool,
                params={"quiz_type": quiz_type},
            )
        ]

    def _handle_review_decision(self, state: SessionState) -> List[OrchestratorAction]:
        return [
            OrchestratorAction(
                type=ActionType.CALL_TOOL,
                tool=ToolName.EXPLAIN_PAGE,
                params={"detail": "DETAILED"},
            ),
            OrchestratorAction(
                type=ActionType.SEND_MESSAGE,
                message="복습이 완료되었습니다. 다시 시험을 볼까요?",
                ui_state={"widget": "RETEST_DECISION"},
            ),
        ]

    # ------------------------------------------------------------------
    # 정책 함수 (설계서 §4.5)
    # ------------------------------------------------------------------

    def _should_use_detailed_explanation(
        self,
        page_state: PageState,
        learner: LearnerModel,
        llm_hint: Optional[dict] = None,
    ) -> bool:
        if llm_hint and llm_hint.get("detailLevel") == "DETAILED":
            return True
        if learner.average_recent_score < 0.6:
            return True
        if page_state.chapter_title and page_state.chapter_title in learner.weak_concepts:
            return True
        return False

    def _should_offer_quiz(
        self,
        page_state: PageState,
        learner: LearnerModel,
        llm_hint: Optional[dict] = None,
    ) -> bool:
        if llm_hint and llm_hint.get("offerQuiz") is True:
            return True
        page_key = str(page_state.page_number)
        attempt_count = learner.quiz_attempt_counts.get(page_key, 0)
        if attempt_count >= 2:
            return False
        if page_state.is_key_page:
            return True
        if learner.average_recent_score < 0.7:
            return True
        return False

    def _decide_detail_level(
        self,
        page_state: PageState,
        learner: LearnerModel,
        llm_hint: Optional[dict] = None,
    ) -> str:
        return "DETAILED" if self._should_use_detailed_explanation(page_state, learner, llm_hint) else "NORMAL"

    def _recommend_quiz_type(self, learner: LearnerModel) -> str:
        match learner.proficiency_level:
            case "BEGINNER":
                return "OX_Problem"
            case "ADVANCED":
                return "Short_Answer"
            case _:
                return "Five_Choice"

    # ------------------------------------------------------------------
    # 공통 액션 빌더
    # ------------------------------------------------------------------

    def _call_generate_quiz(self, quiz_type: str, state: SessionState) -> OrchestratorAction:
        tool_map = {
            "Five_Choice": ToolName.GENERATE_QUIZ_FIVE_CHOICE,
            "OX_Problem": ToolName.GENERATE_QUIZ_OX,
            "Short_Answer": ToolName.GENERATE_QUIZ_SHORT,
            "Flash_Card": ToolName.GENERATE_QUIZ_FLASH,
        }
        tool = tool_map.get(quiz_type, ToolName.GENERATE_QUIZ_FIVE_CHOICE)
        return OrchestratorAction(
            type=ActionType.CALL_TOOL,
            tool=tool,
            params={"quiz_type": quiz_type},
        )

    def _set_ui(self, ui_state: dict) -> OrchestratorAction:
        return OrchestratorAction(
            type=ActionType.SET_UI_STATE,
            ui_state=ui_state,
        )
