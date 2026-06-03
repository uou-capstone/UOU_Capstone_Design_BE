# -*- coding: utf-8 -*-
"""
OrchestrationEngine

Design doc section 3.3: Single-event processing pipeline.

Processing order:
1. Load session (SessionStore)
2. StateReducer.reduce() - apply state changes immediately
3. Orchestrator.run() - build action plan
4. ToolDispatcher.dispatch() - execute actions (streaming)
5. Save session
"""
from __future__ import annotations

import logging
import re
from typing import AsyncGenerator, Optional

from ai_agent.v3.engine.NavigationIntentService import (
    NavigationDirective,
    navigation_intent_service,
)
from ai_agent.v3.engine.Orchestrator import Orchestrator
from ai_agent.v3.engine.QuizDiagnosisService import quiz_diagnosis_service
from ai_agent.v3.engine.StateReducer import StateReducer
from ai_agent.v3.engine.ThoughtTrace import (
    summarize_payload,
    summarize_plan,
    summarize_state,
    trace_event,
)
from ai_agent.v3.engine.ToolDispatcher import ToolDispatcher
from ai_agent.bridge.GeminiBridgeClient import GeminiBridgeClient
from ai_agent.types.domain import (
    ActionType,
    AppEvent,
    AppEventType,
    NdjsonEvent,
    NdjsonEventType,
    OrchestratorAction,
    OrchestratorPlan,
    PageState,
    PageStatus,
    SessionState,
    ToolName,
)
from ai_agent.v3.exam_type_aliases import normalize_exam_type_string
from app.core.session_store import SessionStore
from app.services.error_mapping import stable_error_type

logger = logging.getLogger(__name__)


class OrchestrationEngine:
    """
    Central engine of the multi-agent orchestration layer.
    Called from FastAPI routers to process events and return NDJSON streams.
    """

    def __init__(
        self,
        session_store: SessionStore,
        bridge: Optional[GeminiBridgeClient] = None,
    ):
        self._store = session_store
        self._bridge = bridge or GeminiBridgeClient()
        self._reducer = StateReducer()
        self._orchestrator = Orchestrator(self._bridge)
        self._dispatcher = ToolDispatcher(self._bridge)

    async def handle_event_stream(
        self,
        session_id: int,
        lecture_id: int,
        event: AppEvent,
    ) -> AsyncGenerator[NdjsonEvent, None]:
        """
        Processes an event and streams NdjsonEvents.

        Args:
            session_id: Session ID
            lecture_id: Lecture ID
            event: AppEvent sent by the client
        """
        # 1. Load session
        state = await self._store.get_or_create(session_id, lecture_id)
        event = _normalize_event(event)

        try:
            # 2. Apply state immediately (StateReducer)
            state = self._reducer.reduce(state, event)
            if trace := trace_event(
                "이벤트 수신 및 상태 반영\n"
                f"- event={event.type.value}\n"
                f"- payload={summarize_payload(event.payload)}\n"
                f"- state={summarize_state(state)}"
            ):
                yield trace

            navigation_directive = navigation_intent_service.resolve(event, state)
            if navigation_directive:
                self._apply_navigation_directive(state, navigation_directive)
                yield NdjsonEvent(
                    type=NdjsonEventType.NAVIGATION,
                    targetPage=navigation_directive.target_page,
                    reason=navigation_directive.reason,
                    confidence=navigation_directive.confidence,
                    source=navigation_directive.source,
                )
                if trace := trace_event(
                    "페이지 이동 intent 감지\n"
                    f"- targetPage={navigation_directive.target_page}\n"
                    f"- source={navigation_directive.source}\n"
                    f"- confidence={navigation_directive.confidence:.2f}\n"
                    f"- reason={navigation_directive.reason}"
                ):
                    yield trace
                navigation_plan = self._navigation_plan(navigation_directive, state)
                if trace := trace_event(
                    "페이지 이동 fast plan 생성\n"
                    f"- {summarize_plan(navigation_plan)}"
                ):
                    yield trace
                async for ndjson_event in self._dispatcher.dispatch(
                    navigation_plan,
                    state,
                    event.payload,
                    event_type=event.type.value,
                ):
                    yield ndjson_event
                return

            fast_path_plan = self._fast_path_plan(event, state)
            if fast_path_plan:
                if trace := trace_event(
                    "LLM planner 생략 fast path 선택\n"
                    f"- event={event.type.value}\n"
                    f"- {summarize_plan(fast_path_plan)}"
                ):
                    yield trace
                async for ndjson_event in self._dispatcher.dispatch(
                    fast_path_plan,
                    state,
                    event.payload,
                    event_type=event.type.value,
                ):
                    yield ndjson_event
                return

            # 3. Build plan via LLM (Orchestrator Stream)
            if trace := trace_event(
                "LLM planner 호출\n"
                f"- event={event.type.value}\n"
                f"- state={summarize_state(state)}"
            ):
                yield trace
            plan = None
            async for ndjson_event in self._orchestrator.run_stream(event, state):
                import ai_agent.types.domain as domain_models
                if ndjson_event.type == NdjsonEventType.DONE and ndjson_event.final:
                    if ndjson_event.data and "plan" in ndjson_event.data:
                        plan_data = ndjson_event.data["plan"]
                        if isinstance(plan_data, dict):
                            plan = domain_models.OrchestratorPlan(**plan_data)
                        else:
                            plan = plan_data
                        if trace := trace_event(
                            "LLM planner plan 수신\n"
                            f"- {summarize_plan(plan)}"
                        ):
                            yield trace
                elif ndjson_event.type == NdjsonEventType.AGENT_DELTA:
                    if ndjson_event.channel == "thought":
                        yield ndjson_event
                elif ndjson_event.type == NdjsonEventType.ERROR:
                    yield ndjson_event
                    return
                else:
                    yield ndjson_event

            if plan is None:
                plan = self._fallback_plan_for_empty_actions(event, state)
                if plan is not None:
                    if trace := trace_event(
                        "planner 결과 없음: fallback plan 적용\n"
                        f"- {summarize_plan(plan)}"
                    ):
                        yield trace

            if plan is None:
                yield NdjsonEvent(
                    type=NdjsonEventType.AGENT_DELTA,
                    agent="system",
                    channel="main",
                    delta="다음 학습 동작을 결정하지 못했습니다. 다시 시도해 주세요.",
                )
                yield NdjsonEvent(type=NdjsonEventType.DONE, agent="system", final=True, data={})
                return
            if not plan.actions:
                fallback_plan = self._fallback_plan_for_empty_actions(event, state)
                if fallback_plan is not None:
                    plan = fallback_plan
                    if trace := trace_event(
                        "planner actions 비어 있음: fallback plan 적용\n"
                        f"- {summarize_plan(plan)}"
                    ):
                        yield trace

            # 4. Execute actions (ToolDispatcher)
            if trace := trace_event(
                "ToolDispatcher 실행 시작\n"
                f"- {summarize_plan(plan)}"
            ):
                yield trace
            dispatched = False
            async for ndjson_event in self._dispatcher.dispatch(
                plan,
                state,
                event.payload,
                event_type=event.type.value,
            ):
                dispatched = True
                yield ndjson_event
            if not dispatched:
                yield NdjsonEvent(
                    type=NdjsonEventType.AGENT_DELTA,
                    agent="system",
                    channel="main",
                    delta="다음 학습 동작을 결정하지 못했습니다. 다시 시도해 주세요.",
                )
                yield NdjsonEvent(type=NdjsonEventType.DONE, agent="system", final=True, data={})

        except Exception as exc:
            logger.error(
                f"[OrchestrationEngine] Error processing session_id={session_id}: {exc}",
                exc_info=True,
            )
            yield NdjsonEvent(
                type=NdjsonEventType.ERROR,
                agent="system",
                code="SESSION_EVENT_FAILED",
                message="학습 세션 이벤트 처리 중 오류가 발생했습니다.",
                details=[{"errorType": stable_error_type(exc)}],
            )

        finally:
            # 5. Save session
            try:
                await self._store.set(state)
            except Exception as exc:
                logger.warning(f"[OrchestrationEngine] Session save failed: {exc}")

    async def handle_event(
        self,
        session_id: int,
        lecture_id: int,
        event: AppEvent,
    ) -> dict:
        """
        Non-streaming version: processes all events and returns a final result dict.
        """
        messages = []
        ui_patches = []
        data_patches = []

        async for ev in self.handle_event_stream(session_id, lecture_id, event):
            if ev.type == NdjsonEventType.AGENT_DELTA and ev.channel == "main" and ev.delta:
                messages.append(ev.delta)
            elif ev.type == NdjsonEventType.DONE and ev.data:
                if "ui" in ev.data:
                    ui_patches.append(ev.data["ui"])
                data_patches.append(ev.data)
            elif ev.type == NdjsonEventType.ERROR:
                return {"ok": False, "error": ev.message}

        return {
            "ok": True,
            "message": "".join(messages),
            "ui": ui_patches,
            "data": data_patches,
        }

    def _fast_path_plan(self, event: AppEvent, state: SessionState) -> OrchestratorPlan | None:
        if event.type == AppEventType.USER_MESSAGE:
            message = _event_message_text(event)
            if not message:
                return None
            if _is_abusive_noise(message):
                return OrchestratorPlan(actions=[
                    OrchestratorAction(
                        type=ActionType.SEND_MESSAGE,
                        message="표현은 조금만 조절해 주세요. 막힌 개념이나 페이지를 알려주면 그 부분만 짧게 다시 설명하겠습니다.",
                    )
                ])
            if state.active_intervention:
                return OrchestratorPlan(actions=[
                    OrchestratorAction(
                        type=ActionType.CALL_TOOL,
                        tool=ToolName.REPAIR_MISCONCEPTION,
                        params={"student_message": message},
                    )
                ])
            if _is_explicit_page_navigation_message(message) or _is_explicit_page_explanation_request(message):
                return OrchestratorPlan(actions=[
                    OrchestratorAction(
                        type=ActionType.CALL_TOOL,
                        tool=ToolName.EXPLAIN_PAGE,
                        params=_explain_page_params(state, event.type.value),
                    )
                ])
            if _is_quiz_request_message(message):
                quiz_type = _infer_quiz_type_from_message(message)
                pending_request = _quiz_request_metadata_from_message(message)
                if pending_request:
                    state.pending_quiz_request = pending_request
                if quiz_type:
                    tool = _quiz_generation_tool(quiz_type)
                    if tool:
                        params = {"quiz_type": quiz_type, **pending_request}
                        count = _infer_quiz_count_from_message(message)
                        if count is not None:
                            params["count"] = count
                        return OrchestratorPlan(actions=[
                            OrchestratorAction(
                                type=ActionType.CALL_TOOL,
                                tool=tool,
                                params=params,
                            )
                        ])
                return OrchestratorPlan(actions=[
                    OrchestratorAction(
                        type=ActionType.SET_UI_STATE,
                        ui_state={"modal": "QUIZ_TYPE_PICKER", "reason": "USER_QUIZ_REQUEST"},
                    )
                ])
            return OrchestratorPlan(actions=[
                OrchestratorAction(
                    type=ActionType.CALL_TOOL,
                    tool=ToolName.ANSWER_QUESTION,
                    params={"question": message},
                )
            ])
        if event.type == AppEventType.QUIZ_TYPE_SELECTED:
            quiz_type = _event_quiz_type(event)
            tool = _quiz_generation_tool(quiz_type)
            if tool:
                return OrchestratorPlan(actions=[
                    OrchestratorAction(
                        type=ActionType.CALL_TOOL,
                        tool=tool,
                        params=_quiz_generation_params_from_event(event, state, quiz_type),
                    )
                ])

        if event.type == AppEventType.REVIEW_DECISION and _event_accepts(event):
            if state.active_intervention:
                return OrchestratorPlan(actions=[
                    OrchestratorAction(
                        type=ActionType.CALL_TOOL,
                        tool=ToolName.REPAIR_MISCONCEPTION,
                        params={"student_message": _event_message_text(event)},
                    )
                ])
            return OrchestratorPlan(actions=[
                OrchestratorAction(
                    type=ActionType.CALL_TOOL,
                    tool=ToolName.EXPLAIN_PAGE,
                    params={"detail": "DETAILED", "next_widget": "RETEST_DECISION"},
                )
            ])

        if event.type != AppEventType.QUIZ_SUBMITTED:
            return None
        latest = self._latest_page_quiz(state)
        if not latest:
            return None
        quiz_type = _normalize_quiz_type_text(
            event.get("quiz_type", event.get("quizType", event.get("exam_type", latest.quiz_type)))
        )
        if quiz_type not in {"Five_Choice", "OX_Problem"}:
            return None
        return OrchestratorPlan(actions=[
            OrchestratorAction(
                type=ActionType.CALL_TOOL,
                tool=ToolName.AUTO_GRADE_MCQ_OX,
                params={"quiz_type": quiz_type},
            )
        ])

    def _navigation_plan(self, directive: NavigationDirective, state: SessionState) -> OrchestratorPlan:
        return OrchestratorPlan(actions=[
            OrchestratorAction(
                type=ActionType.CALL_TOOL,
                tool=ToolName.EXPLAIN_PAGE,
                params=_explain_page_params(state, AppEventType.PAGE_CHANGED.value),
            )
        ])

    def _apply_navigation_directive(
        self,
        state: SessionState,
        directive: NavigationDirective,
    ) -> None:
        target_page = max(1, int(directive.target_page))
        if state.current_page != target_page:
            current_page_state = state.get_current_page_state()
            current_page_state.status = PageStatus.DONE
        state.current_page = target_page
        if state.current_page not in state.pages:
            state.pages[state.current_page] = PageState(page_number=state.current_page)

    def _fallback_plan_for_empty_actions(self, event: AppEvent, state: SessionState) -> OrchestratorPlan | None:
        if event.type == AppEventType.SESSION_ENTERED:
            return OrchestratorPlan(actions=[
                OrchestratorAction(
                    type=ActionType.SET_UI_STATE,
                    ui_state={"widget": "START_EXPLANATION_DECISION"},
                )
            ])
        if event.type == AppEventType.START_EXPLANATION_DECISION and _event_accepts(event):
            return OrchestratorPlan(actions=[
                OrchestratorAction(
                    type=ActionType.CALL_TOOL,
                    tool=ToolName.EXPLAIN_PAGE,
                    params=_explain_page_params(state, event.type.value),
                )
            ])
        if event.type == AppEventType.PAGE_CHANGED:
            return OrchestratorPlan(actions=[
                OrchestratorAction(
                    type=ActionType.CALL_TOOL,
                    tool=ToolName.EXPLAIN_PAGE,
                    params=_explain_page_params(state, event.type.value),
                )
            ])
        if event.type == AppEventType.USER_MESSAGE:
            question = _event_message_text(event)
            if question and not state.active_intervention:
                return OrchestratorPlan(actions=[
                    OrchestratorAction(
                        type=ActionType.CALL_TOOL,
                        tool=ToolName.ANSWER_QUESTION,
                        params={"question": question},
                    )
                ])
        if event.type == AppEventType.NEXT_PAGE_DECISION and _event_accepts(event):
            return OrchestratorPlan(actions=[
                OrchestratorAction(
                    type=ActionType.CALL_TOOL,
                    tool=ToolName.EXPLAIN_PAGE,
                    params=_explain_page_params(state, event.type.value),
                )
            ])
        if event.type == AppEventType.QUIZ_DECISION and _event_accepts(event):
            return OrchestratorPlan(actions=[
                OrchestratorAction(
                    type=ActionType.SET_UI_STATE,
                    ui_state={"modal": "QUIZ_TYPE_PICKER"},
                )
            ])
        if event.type == AppEventType.RETEST_DECISION and _event_accepts(event):
            return OrchestratorPlan(actions=[
                OrchestratorAction(
                    type=ActionType.SET_UI_STATE,
                    ui_state={"modal": "QUIZ_TYPE_PICKER", "mode": "RETEST"},
                )
            ])
        if event.type == AppEventType.QUIZ_TYPE_SELECTED:
            quiz_type = _event_quiz_type(event)
            tool = _quiz_generation_tool(quiz_type)
            if tool:
                return OrchestratorPlan(actions=[
                    OrchestratorAction(
                        type=ActionType.CALL_TOOL,
                        tool=tool,
                        params=_quiz_generation_params_from_event(event, state, quiz_type),
                    )
                ])
        if event.type == AppEventType.QUIZ_SUBMITTED:
            quiz_type = _event_or_latest_quiz_type(event, state)
            if quiz_type in {"Short_Answer", "Essay"}:
                return OrchestratorPlan(actions=[
                    OrchestratorAction(
                        type=ActionType.CALL_TOOL,
                        tool=ToolName.GRADE_SHORT_OR_ESSAY,
                        params={"quiz_type": quiz_type},
                    )
                ])
        return None

    @staticmethod
    def _latest_page_quiz(state: SessionState):
        for record in reversed(state.quiz_history):
            if record.page_number == state.current_page:
                return record
        return None


def _event_accepts(event: AppEvent) -> bool:
    if not event.payload:
        return True
    value = event.get("accept", event.get("accepted", event.get("decision", event.get("start", True))))
    if isinstance(value, str):
        return value.strip().lower() in {"true", "yes", "y", "1", "accept", "accepted", "start", "next"}
    return bool(value)


def _explain_page_params(
    state: SessionState | None,
    event_type: str | None,
    *,
    detail: str = "NORMAL",
) -> dict[str, str]:
    return {
        "detail": detail,
        "next_widget": _followup_widget_after_explanation(state, event_type),
    }


def _followup_widget_after_explanation(
    state: SessionState | None,
    event_type: str | None,
) -> str:
    """
    Reference-style post-explanation flow:
    after an explanation, ask whether the learner wants an understanding
    check. Do not generate the quiz until the learner accepts and selects
    a quiz type.
    """
    if state is None:
        return "QUIZ_DECISION"

    current_page = max(int(state.current_page or 1), 1)
    if _page_has_quiz_activity(state, current_page):
        return "NEXT_PAGE_DECISION"

    return "QUIZ_DECISION"


def _page_has_quiz_activity(state: SessionState, page_number: int) -> bool:
    if state.learner.quiz_attempt_counts.get(str(page_number), 0) > 0:
        return True
    return any(record.page_number == page_number for record in state.quiz_history)


def _event_message_text(event: AppEvent) -> str:
    return str(_payload_message_text(event.payload)).strip()


def _normalize_event(event: AppEvent) -> AppEvent:
    payload = dict(event.payload or {})
    nested = payload.get("payload")
    if isinstance(nested, dict):
        outer = {key: value for key, value in payload.items() if key != "payload"}
        payload = {**nested, **outer}

    if event.type == AppEventType.USER_MESSAGE:
        message = _payload_message_text(payload)
        if message:
            payload["question"] = message
            payload.setdefault("text", message)

    return event.model_copy(update={"payload": payload})


def _payload_message_text(payload: dict) -> str:
    for key in ("question", "text", "message", "content", "userMessage", "prompt"):
        value = payload.get(key)
        if value is None:
            continue
        text = str(value).strip()
        if text:
            return text
    return ""


_ABUSIVE_RE = re.compile(r"(좆|ㅈ같|씨발|시발|ㅅㅂ|개새|병신|꺼져|fuck|shit)", re.IGNORECASE)
_LEARNING_SIGNAL_RE = re.compile(
    r"(이해|모르|헷갈|설명|알려|뭐|무엇|왜|어떻게|tcp|udp|flow|control|페이지|슬라이드|문제|\?)",
    re.IGNORECASE,
)
_EXPLICIT_PAGE_EXPLANATION_RE = re.compile(
    r"((현재|이|지금)\s*(페이지|슬라이드)(의|를|을|에서)?\s*(전체|내용)?\s*(설명|강의|요약)"
    r"|"
    r"(페이지|슬라이드)\s*전체\s*(설명|강의|요약)"
    r"|"
    r"\d+\s*(페이지|슬라이드)\s*(전체|내용)?\s*(설명|강의|요약)"
    r"|"
    r"(설명|강의|요약).*((현재|이|지금)\s*(페이지|슬라이드)|(페이지|슬라이드)\s*전체))"
)
_EXPLICIT_PAGE_NAVIGATION_MESSAGE_RE = re.compile(
    r"^\s*\d{1,4}\s*(페이지|쪽|page|p\b)\s*(ㄱ+ㄱ*ㄹ?|가줘|가자|이동|보여|열어|설명)?\s*$",
    re.IGNORECASE,
)
_QUIZ_REQUEST_RE = re.compile(
    r"("
    r"(퀴즈|quiz|시험|테스트|문제|문항).{0,18}(만들|생성|내|내줘|출제|풀|풀어|보자|볼래|진행|확인|연습|줘)"
    r"|"
    r"(만들|생성|내|내줘|출제|풀|풀어|보자|볼래|진행|확인|연습).{0,18}(퀴즈|quiz|시험|테스트|문제|문항)"
    r"|"
    r"(복습|연습|확인|점검|자가\s*진단|자기\s*진단).{0,12}(문제|문항|퀴즈|시험|테스트|문제\s*풀이)"
    r"|"
    r"(객관식|5지선다|오지선다|mcq|multiple\s*choice|ox|o/x|오엑스|참거짓|true\s*false|단답|서술|논술|essay|플래시\s*카드|플래시카드|flash\s*card).{0,18}(퀴즈|시험|문제|문항|만들|생성|내|내줘|출제)"
    r")",
    re.IGNORECASE,
)
_QUIZ_CHECK_REQUEST_RE = re.compile(
    r"("
    r"(내가|제가|나|우리)?.{0,8}(이해|학습|공부|내용).{0,16}(했는지|한\s*건지|됐는지|되는지|수준|상태)?.{0,12}(확인|점검|체크|테스트|자가\s*진단|자기\s*진단)"
    r"|"
    r"(이해|학습|공부|내용).{0,12}(확인|점검|체크|테스트|자가\s*진단|자기\s*진단).{0,12}(해줘|해\s*줘|하고\s*싶|볼래|해볼래)"
    r")",
    re.IGNORECASE,
)
_QUIZ_COUNT_RE = re.compile(r"(\d{1,2})\s*(개|문항|문제|questions?)", re.IGNORECASE)
_PAGE_RANGE_PATTERNS: tuple[re.Pattern[str], ...] = (
    re.compile(r"(?:페이지|page|p)?\s*(\d{1,4})\s*(?:~|-|부터|에서)\s*(\d{1,4})\s*(?:페이지|쪽|page|p)?", re.IGNORECASE),
    re.compile(r"(?:페이지|page|p)\s*(\d{1,4})\s*(?:부터|에서|~|-)\s*(\d{1,4})", re.IGNORECASE),
)
_QUIZ_TYPE_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"(객관식|5\s*지|오지선다|five\s*choice|multiple\s*choice|mcq)", re.IGNORECASE), "Five_Choice"),
    (re.compile(r"(\bOX\b|O/X|오엑스|참\s*거짓|true\s*/?\s*false|true\s*false)", re.IGNORECASE), "OX_Problem"),
    (re.compile(r"(플래시\s*카드|플래시카드|flash\s*card)", re.IGNORECASE), "Flash_Card"),
    (re.compile(r"(단답|short\s*answer|short)", re.IGNORECASE), "Short_Answer"),
    (re.compile(r"(서술|논술|essay|subjective)", re.IGNORECASE), "Essay"),
)


def _is_abusive_noise(message: str) -> bool:
    return bool(_ABUSIVE_RE.search(message) and not _LEARNING_SIGNAL_RE.search(message))


def _is_explicit_page_explanation_request(message: str) -> bool:
    return bool(_EXPLICIT_PAGE_EXPLANATION_RE.search(message.strip()))


def _is_explicit_page_navigation_message(message: str) -> bool:
    return bool(_EXPLICIT_PAGE_NAVIGATION_MESSAGE_RE.search(message.strip()))


def _is_quiz_request_message(message: str) -> bool:
    text = message.strip()
    return bool(_QUIZ_REQUEST_RE.search(text) or _QUIZ_CHECK_REQUEST_RE.search(text))


def _infer_quiz_type_from_message(message: str) -> str:
    text = message.strip()
    for pattern, quiz_type in _QUIZ_TYPE_PATTERNS:
        if pattern.search(text):
            return quiz_type
    return ""


def _infer_quiz_count_from_message(message: str) -> int | None:
    match = _QUIZ_COUNT_RE.search(message.strip())
    if not match:
        return None
    count = int(match.group(1))
    return max(1, min(count, 20))


def _quiz_request_metadata_from_message(message: str) -> dict[str, object]:
    metadata: dict[str, object] = {"source_request": message.strip()}
    page_range = _infer_quiz_page_range_from_message(message)
    if page_range:
        start, end = page_range
        metadata["coverage_start_page"] = start
        metadata["coverage_end_page"] = end
    count = _infer_quiz_count_from_message(message)
    if count is not None:
        metadata["count"] = count
    return metadata


def _infer_quiz_page_range_from_message(message: str) -> tuple[int, int] | None:
    text = message.strip()
    for pattern in _PAGE_RANGE_PATTERNS:
        match = pattern.search(text)
        if not match:
            continue
        start = int(match.group(1))
        end = int(match.group(2))
        if start <= 0 or end <= 0:
            return None
        return (min(start, end), max(start, end))
    return None


def _quiz_generation_params_from_event(
    event: AppEvent,
    state: SessionState,
    quiz_type: str,
) -> dict[str, object]:
    params: dict[str, object] = {"quiz_type": quiz_type}
    pending = state.pending_quiz_request or {}
    for key in ("coverage_start_page", "coverage_end_page", "source_request", "count"):
        if key in pending:
            params[key] = pending[key]

    payload_key_map = {
        "coverage_start_page": ("coverage_start_page", "coverageStartPage", "startPage"),
        "coverage_end_page": ("coverage_end_page", "coverageEndPage", "endPage"),
        "source_request": ("source_request", "sourceRequest", "question", "text", "message"),
        "count": ("count", "questionCount", "numQuestions"),
    }
    for normalized_key, raw_keys in payload_key_map.items():
        for raw_key in raw_keys:
            value = event.get(raw_key)
            if value is not None and value != "":
                params[normalized_key] = value
                break

    for key in ("coverage_start_page", "coverage_end_page", "count"):
        if key not in params:
            continue
        try:
            value = int(params[key])
        except (TypeError, ValueError):
            params.pop(key, None)
            continue
        if key == "count":
            params[key] = max(1, min(value, 20))
        else:
            params[key] = max(1, value)

    start = params.get("coverage_start_page")
    end = params.get("coverage_end_page")
    if isinstance(start, int) and isinstance(end, int) and start > end:
        params["coverage_start_page"], params["coverage_end_page"] = end, start
    return params


def _event_quiz_type(event: AppEvent) -> str:
    raw = event.get(
        "quiz_type",
        event.get(
            "quizType",
            event.get("exam_type", event.get("examType", event.get("selectedType", ""))),
        ),
    )
    return _normalize_quiz_type_text(raw)


def _event_or_latest_quiz_type(event: AppEvent, state: SessionState) -> str:
    latest = OrchestrationEngine._latest_page_quiz(state)
    raw = event.get(
        "quiz_type",
        event.get(
            "quizType",
            event.get("exam_type", event.get("examType", latest.quiz_type if latest else "")),
        ),
    )
    quiz_type = _normalize_quiz_type_text(raw)
    if quiz_type:
        return quiz_type
    return latest.quiz_type if latest else ""


def _normalize_quiz_type_text(raw: object) -> str:
    return normalize_exam_type_string(str(raw or "").strip())


def _quiz_generation_tool(quiz_type: str) -> ToolName | None:
    if quiz_type == "Five_Choice":
        return ToolName.GENERATE_QUIZ_FIVE_CHOICE
    if quiz_type == "OX_Problem":
        return ToolName.GENERATE_QUIZ_OX
    if quiz_type == "Short_Answer":
        return ToolName.GENERATE_QUIZ_SHORT
    if quiz_type == "Essay":
        return ToolName.GENERATE_QUIZ_ESSAY
    if quiz_type == "Flash_Card":
        return ToolName.GENERATE_QUIZ_FLASH
    return None
