"""
StateReducer

설계서 §7.3: 이벤트 도착 즉시 선반영(페이지 이동/유저 메시지/기본 상태 전이).
ToolDispatcher 의 실행 결과 반영(설명 완료, 퀴즈 생성/채점)은 ToolDispatcher 담당.
"""
from __future__ import annotations

from datetime import datetime, timezone

from ai_agent.types.domain import (
    AppEvent,
    AppEventType,
    PageState,
    PageStatus,
    SessionState,
)
from ai_agent.v3.engine.PageCommandIntent import get_page_command_intent


class StateReducer:
    """
    이벤트 발생 즉시 SessionState 를 선반영하는 순수 함수형 리듀서.
    모든 메서드는 state 를 in-place 로 수정하고 반환한다.
    """

    def reduce(self, state: SessionState, event: AppEvent) -> SessionState:
        """
        이벤트 타입에 따라 상태를 선반영한다.
        실제 AI 호출 결과는 반영하지 않는다 (ToolDispatcher 담당).
        """
        now = datetime.now(timezone.utc).isoformat()
        state.updated_at = now

        if event.type not in {AppEventType.SESSION_ENTERED, AppEventType.NEXT_PAGE_DECISION}:
            _sync_current_page_from_event(state, event)

        match event.type:
            case AppEventType.SESSION_ENTERED:
                self._on_session_entered(state, event)
            case AppEventType.PAGE_CHANGED:
                self._on_page_changed(state, event)
            case AppEventType.USER_MESSAGE:
                self._on_user_message(state, event)
            case AppEventType.QUIZ_DECISION:
                self._on_quiz_decision(state, event)
            case AppEventType.QUIZ_TYPE_SELECTED:
                self._on_quiz_type_selected(state, event)
            case AppEventType.QUIZ_SUBMITTED:
                self._on_quiz_submitted(state, event)
            case AppEventType.REVIEW_DECISION:
                self._on_review_decision(state, event)
            case AppEventType.RETEST_DECISION:
                self._on_retest_decision(state, event)
            case AppEventType.NEXT_PAGE_DECISION:
                self._on_next_page_decision(state, event)
            case AppEventType.SAVE_AND_EXIT:
                pass

        return state

    # ------------------------------------------------------------------
    # 개별 이벤트 핸들러
    # ------------------------------------------------------------------

    def _on_session_entered(self, state: SessionState, event: AppEvent) -> None:
        state.get_current_page_state()

    def _on_page_changed(self, state: SessionState, event: AppEvent) -> None:
        _sync_current_page_from_event(state, event)

    def _on_user_message(self, state: SessionState, event: AppEvent) -> None:
        text = _event_text(event)
        state.append_message("user", text)
        intent = get_page_command_intent(text)
        if intent == "NEXT":
            current_page_state = state.get_current_page_state()
            current_page_state.status = PageStatus.DONE
            state.current_page = max(state.current_page, 1) + 1
            if state.current_page not in state.pages:
                state.pages[state.current_page] = PageState(page_number=state.current_page)
        elif intent == "PREVIOUS":
            state.current_page = max(1, state.current_page - 1)
            if state.current_page not in state.pages:
                state.pages[state.current_page] = PageState(page_number=state.current_page)

    def _on_quiz_decision(self, state: SessionState, event: AppEvent) -> None:
        page_state = state.get_current_page_state()
        page_state.status = PageStatus.QUIZ_TYPE_PENDING if _event_accepts(event) else PageStatus.DONE

    def _on_quiz_type_selected(self, state: SessionState, event: AppEvent) -> None:
        page_state = state.get_current_page_state()
        page_state.status = PageStatus.QUIZ_IN_PROGRESS

    def _on_quiz_submitted(self, state: SessionState, event: AppEvent) -> None:
        page_state = state.get_current_page_state()
        page_state.status = PageStatus.QUIZ_GRADED

    def _on_review_decision(self, state: SessionState, event: AppEvent) -> None:
        page_state = state.get_current_page_state()
        page_state.status = PageStatus.REVIEW_IN_PROGRESS if _event_accepts(event) else PageStatus.DONE

    def _on_retest_decision(self, state: SessionState, event: AppEvent) -> None:
        page_state = state.get_current_page_state()
        page_state.status = PageStatus.QUIZ_TYPE_PENDING if _event_accepts(event) else PageStatus.DONE

    def _on_next_page_decision(self, state: SessionState, event: AppEvent) -> None:
        if not _event_accepts(event):
            return
        _advance_from_next_page_decision(state, event)


def _event_accepts(event: AppEvent) -> bool:
    value = event.get("accept", event.get("accepted", event.get("decision", False)))
    if isinstance(value, str):
        return value.strip().lower() in {"true", "yes", "y", "1", "accept", "accepted", "next"}
    return bool(value)


def _event_text(event: AppEvent) -> str:
    value = event.get(
        "question",
        event.get(
            "text",
            event.get("message", event.get("content", event.get("userMessage", ""))),
        ),
    )
    return str(value or "").strip()


def _advance_from_next_page_decision(state: SessionState, event: AppEvent) -> None:
    previous_page = max(state.current_page, 1)
    target_page = _page_number_from_event(
        event,
        keys=("targetPage", "target_page", "toPage", "to_page"),
    )
    from_page = _page_number_from_event(event, keys=("fromPage", "from_page"))
    visible_page = _page_number_from_event(
        event,
        keys=("page", "pageNumber", "page_number", "currentPage", "current_page"),
    )

    if target_page is not None:
        next_page = target_page
    elif from_page is not None:
        previous_page = from_page
        next_page = from_page + 1
    elif visible_page is not None and visible_page > state.current_page:
        next_page = visible_page
    else:
        next_page = state.current_page + 1

    if previous_page not in state.pages:
        state.pages[previous_page] = PageState(page_number=previous_page)
    state.pages[previous_page].status = PageStatus.DONE
    state.current_page = max(next_page, 1)
    if state.current_page not in state.pages:
        state.pages[state.current_page] = PageState(page_number=state.current_page)


def _sync_current_page_from_event(
    state: SessionState,
    event: AppEvent,
    *,
    keys: tuple[str, ...] = ("page", "pageNumber", "page_number", "currentPage", "current_page"),
) -> None:
    page = _page_number_from_event(event, keys=keys)
    if page is None:
        return
    state.current_page = page
    if state.current_page not in state.pages:
        state.pages[state.current_page] = PageState(page_number=state.current_page)


def _page_number_from_event(event: AppEvent, *, keys: tuple[str, ...]) -> int | None:
    for key in keys:
        raw_page = event.get(key)
        if raw_page is None:
            continue
        try:
            page = int(raw_page)
        except (ValueError, TypeError):
            continue
        if page >= 1:
            return page
    return None
