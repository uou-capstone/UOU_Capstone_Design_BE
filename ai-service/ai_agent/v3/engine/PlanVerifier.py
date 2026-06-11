from __future__ import annotations

from dataclasses import dataclass
import re
from typing import Any

from ai_agent.types.domain import (
    ActionType,
    AppEventType,
    OrchestratorAction,
    OrchestratorPlan,
    PedagogyMode,
    SessionState,
    ToolName,
)
from ai_agent.v3.exam_type_aliases import normalize_exam_type_string
from app.services.pdf_context_service import pdf_context_service


ACTION_HARD_CAP = 8

_PAGE_EXPLANATION_RE = re.compile(
    r"((현재|이|지금)\s*(페이지|슬라이드)(의|를|을|에서)?\s*(전체|내용)?\s*(설명|강의|요약)"
    r"|"
    r"(페이지|슬라이드)\s*전체\s*(설명|강의|요약)"
    r"|"
    r"\d+\s*(페이지|슬라이드)\s*(전체|내용)?\s*(설명|강의|요약)"
    r"|"
    r"(설명|강의|요약).*((현재|이|지금)\s*(페이지|슬라이드)|(페이지|슬라이드)\s*전체))"
)

_NEXT_PAGE_DECISION_MESSAGE_RE = re.compile(
    r"(다음\s*(페이지|슬라이드).*(넘어|이동|진행)|"
    r"(넘어|이동|진행)할까요\??|"
    r"next\s*(page|slide))",
    re.IGNORECASE,
)

_QUIZ_DECISION_MESSAGE_RE = re.compile(
    r"((퀴즈|시험|문제).*(진행|시작|풀|볼).*까요\??|"
    r"(진행|시작|풀|볼).*?(퀴즈|시험|문제)|"
    r"quiz)",
    re.IGNORECASE,
)

_RETEST_DECISION_MESSAGE_RE = re.compile(
    r"((재시험|다시\s*(풀|확인|도전)).*(진행|시작|볼).*까요\??|"
    r"retest)",
    re.IGNORECASE,
)

_ABUSIVE_RE = re.compile(r"(좆|ㅈ같|씨발|시발|ㅅㅂ|개새|병신|꺼져|fuck|shit)", re.IGNORECASE)
_LEARNING_SIGNAL_RE = re.compile(
    r"(이해|모르|헷갈|설명|알려|뭐|무엇|왜|어떻게|tcp|udp|flow|control|페이지|슬라이드|문제|\?)",
    re.IGNORECASE,
)

_PLANNER_ALLOWED_TOOLS = {
    ToolName.EXPLAIN_PAGE,
    ToolName.ANSWER_QUESTION,
    ToolName.GENERATE_QUIZ_FIVE_CHOICE,
    ToolName.GENERATE_QUIZ_OX,
    ToolName.GENERATE_QUIZ_SHORT,
    ToolName.GENERATE_QUIZ_ESSAY,
    ToolName.GENERATE_QUIZ_FLASH,
    ToolName.AUTO_GRADE_MCQ_OX,
    ToolName.GRADE_SHORT_OR_ESSAY,
    ToolName.REPAIR_MISCONCEPTION,
}

_INTERVENTION_TOOLS = {
    ToolName.EXPLAIN_PAGE,
    ToolName.GENERATE_QUIZ_FIVE_CHOICE,
    ToolName.GENERATE_QUIZ_OX,
    ToolName.GENERATE_QUIZ_SHORT,
    ToolName.GENERATE_QUIZ_ESSAY,
    ToolName.GENERATE_QUIZ_FLASH,
    ToolName.GRADE_SHORT_OR_ESSAY,
    ToolName.REPAIR_MISCONCEPTION,
}

_QUIZ_FLOW_TOOLS = {
    ToolName.GENERATE_QUIZ_FIVE_CHOICE,
    ToolName.GENERATE_QUIZ_OX,
    ToolName.GENERATE_QUIZ_SHORT,
    ToolName.GENERATE_QUIZ_ESSAY,
    ToolName.GENERATE_QUIZ_FLASH,
}
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
_QUIZ_TYPE_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"(객관식|5\s*지|오지선다|five\s*choice|multiple\s*choice|mcq)", re.IGNORECASE), "Five_Choice"),
    (re.compile(r"(\bOX\b|O/X|오엑스|참\s*거짓|true\s*/?\s*false|true\s*false)", re.IGNORECASE), "OX_Problem"),
    (re.compile(r"(플래시\s*카드|플래시카드|flash\s*card)", re.IGNORECASE), "Flash_Card"),
    (re.compile(r"(단답|short\s*answer|short)", re.IGNORECASE), "Short_Answer"),
    (re.compile(r"(서술|논술|essay|subjective)", re.IGNORECASE), "Essay"),
)
_QUIZ_COUNT_RE = re.compile(r"(\d{1,2})\s*(개|문항|문제|questions?)", re.IGNORECASE)
_PAGE_RANGE_PATTERNS: tuple[re.Pattern[str], ...] = (
    re.compile(r"(?:페이지|page|p)?\s*(\d{1,4})\s*(?:~|-|부터|에서)\s*(\d{1,4})\s*(?:페이지|쪽|page|p)?", re.IGNORECASE),
    re.compile(r"(?:페이지|page|p)\s*(\d{1,4})\s*(?:부터|에서|~|-)\s*(\d{1,4})", re.IGNORECASE),
)
_SINGLE_PAGE_QUIZ_PATTERNS: tuple[re.Pattern[str], ...] = (
    re.compile(r"(\d{1,4})\s*(?:페이지|쪽|page|p\b).{0,24}(퀴즈|quiz|시험|테스트|문제|문항)", re.IGNORECASE),
    re.compile(r"(퀴즈|quiz|시험|테스트|문제|문항).{0,24}(\d{1,4})\s*(?:페이지|쪽|page|p\b)", re.IGNORECASE),
)
_PAGE_TOKEN_RE = re.compile(r"[A-Za-z가-힣0-9]+")
_NON_QUIZ_PAGE_MARKER_RE = re.compile(
    r"(표지|목차|차례|contents|table\s+of\s+contents|overview|개요|인트로|introduction|"
    r"intro|학습\s*목표|로드맵|roadmap|수능특강|과학탐구영역|chapter\s*\d*\s*:?\s*$)",
    re.IGNORECASE,
)
_QUIZ_WORTHY_SIGNAL_RE = re.compile(
    r"(정의|공식|법칙|원리|특징|조건|비교|차이|과정|단계|계산|예시|문제|그래프|실험|"
    r"증명|분류|관계|작용|속도|가속도|힘|에너지|운동량|전류|전압|저항|파동|주파수|"
    r"통신|프로토콜|tcp|udp|ack|buffer|jitter|delay|coding|flow\s*control|congestion|"
    r"multiplexing|demultiplexing|reliable)",
    re.IGNORECASE,
)
_EXPLANATORY_SENTENCE_RE = re.compile(
    r"(이다|합니다|한다|된다|됩니다|때문|의미|역할|사용|통해|따라|비해|because|means|is|are|used|provides)",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class PlanVerificationResult:
    plan: OrchestratorPlan
    warnings: list[dict[str, Any]]


class PlanVerifier:
    """
    Soft safety layer for LLM planner output.

    The verifier rejects malformed or excessive actions and can route the next
    user turn into the limited misconception-repair loop when an intervention is
    active.
    """

    def verify(
        self,
        plan: OrchestratorPlan,
        state: SessionState,
        *,
        event_type: str | None = None,
        event_payload: dict[str, Any] | None = None,
    ) -> PlanVerificationResult:
        warnings: list[dict[str, Any]] = []
        verified_actions: list[OrchestratorAction] = []
        intervention_count = 0
        intervention_budget = self._normal_intervention_budget(plan)
        candidate_actions = self._repair_first_actions(
            plan.actions,
            state,
            event_type=event_type,
            event_payload=event_payload or {},
            warnings=warnings,
        )
        candidate_actions = self._patch_user_message_question_actions(
            candidate_actions,
            state,
            event_type=event_type,
            event_payload=event_payload or {},
            warnings=warnings,
        )
        candidate_actions = self._patch_event_followup_widgets(
            candidate_actions,
            state,
            event_type=event_type,
            event_payload=event_payload or {},
            warnings=warnings,
        )
        candidate_actions = self._patch_decision_send_message_widgets(
            candidate_actions,
            warnings=warnings,
        )

        if plan.stop:
            warnings.append(self._warning(
                "PLAN_STOP_REQUESTED",
                "Planner requested stop; no actions will be executed.",
            ))
            return self._result(plan, [], warnings, state)

        for index, action in enumerate(candidate_actions):
            if len(verified_actions) >= ACTION_HARD_CAP:
                warnings.append(self._warning(
                    "ACTION_HARD_CAP_TRIMMED",
                    f"Actions after index {index - 1} were trimmed by hard cap {ACTION_HARD_CAP}.",
                    action_index=index,
                ))
                break

            if action.type == ActionType.CALL_TOOL:
                if action.tool is None:
                    warnings.append(self._warning(
                        "MISSING_TOOL_DROPPED",
                        "CALL_TOOL action without a tool was dropped.",
                        action_index=index,
                    ))
                    continue
                if action.tool not in _PLANNER_ALLOWED_TOOLS:
                    warnings.append(self._warning(
                        "UNALLOWED_TOOL_DROPPED",
                        "CALL_TOOL action with a non-planner tool was dropped.",
                        action_index=index,
                        tool=action.tool.value,
                    ))
                    continue
                if action.tool in _INTERVENTION_TOOLS:
                    if intervention_count >= intervention_budget:
                        warnings.append(self._warning(
                            "INTERVENTION_BUDGET_TRIMMED",
                            f"Intervention action exceeded budget {intervention_budget}.",
                            action_index=index,
                            tool=action.tool.value,
                        ))
                        continue
                    intervention_count += 1

            verified_actions.append(action)

        policy = plan.pedagogy_policy
        if policy.mode == PedagogyMode.HOLD_BACK and verified_actions:
            warnings.append(self._warning(
                "HOLD_BACK_POLICY_REVIEW_REQUIRED",
                "HOLD_BACK policy was present; actions were kept for compatibility and flagged for review.",
            ))
        if not policy.allow_direct_answer and self._has_answer_question(verified_actions):
            warnings.append(self._warning(
                "DIRECT_ANSWER_POLICY_REVIEW_REQUIRED",
                "Direct answer policy is disabled; ANSWER_QUESTION action was kept and flagged for review.",
            ))
        if policy.mode == PedagogyMode.MISCONCEPTION_REPAIR and state.active_intervention:
            warnings.append(self._warning(
                "REPAIR_POLICY_ACTIVE",
                "MISCONCEPTION_REPAIR policy is active for the current intervention.",
            ))

        return self._result(plan, verified_actions, warnings, state)

    def _result(
        self,
        original: OrchestratorPlan,
        actions: list[OrchestratorAction],
        warnings: list[dict[str, Any]],
        state: SessionState,
    ) -> PlanVerificationResult:
        existing = list(original.verification_warnings or [])
        all_warnings = [*existing, *warnings]
        verified = original.model_copy(update={
            "actions": actions,
            "verification_warnings": all_warnings,
        })
        state.plan_verification_warnings = all_warnings[-20:]
        return PlanVerificationResult(plan=verified, warnings=all_warnings)

    @staticmethod
    def _normal_intervention_budget(plan: OrchestratorPlan) -> int:
        value = plan.pedagogy_policy.intervention_budget
        try:
            return max(0, min(int(value), ACTION_HARD_CAP))
        except (TypeError, ValueError):
            return 2

    @staticmethod
    def _has_answer_question(actions: list[OrchestratorAction]) -> bool:
        return any(action.type == ActionType.CALL_TOOL and action.tool == ToolName.ANSWER_QUESTION for action in actions)

    def _repair_first_actions(
        self,
        actions: list[OrchestratorAction],
        state: SessionState,
        *,
        event_type: str | None,
        event_payload: dict[str, Any],
        warnings: list[dict[str, Any]],
    ) -> list[OrchestratorAction]:
        if event_type != AppEventType.USER_MESSAGE.value:
            return list(actions)
        intervention = state.active_intervention
        if not intervention or intervention.get("status") in {"COMPLETED", "RESOLVED_BY_RETEST", "CANCELLED"}:
            return list(actions)
        if any(action.type == ActionType.CALL_TOOL and action.tool == ToolName.REPAIR_MISCONCEPTION for action in actions):
            return list(actions)

        student_message = _event_user_message(event_payload)
        repair_action = OrchestratorAction(
            type=ActionType.CALL_TOOL,
            tool=ToolName.REPAIR_MISCONCEPTION,
            params={"student_message": student_message},
        )
        filtered = [
            action for action in actions
            if not (action.type == ActionType.CALL_TOOL and action.tool == ToolName.ANSWER_QUESTION)
        ]
        warnings.append(self._warning(
            "REPAIR_MISCONCEPTION_INJECTED",
            "Active intervention detected; repair tool was prioritized for this user turn.",
            tool=ToolName.REPAIR_MISCONCEPTION.value,
        ))
        return [repair_action, *filtered]

    def _patch_user_message_question_actions(
        self,
        actions: list[OrchestratorAction],
        state: SessionState,
        *,
        event_type: str | None,
        event_payload: dict[str, Any],
        warnings: list[dict[str, Any]],
    ) -> list[OrchestratorAction]:
        if event_type != AppEventType.USER_MESSAGE.value:
            return list(actions)

        intervention = state.active_intervention
        if intervention and intervention.get("status") not in {"COMPLETED", "RESOLVED_BY_RETEST", "CANCELLED"}:
            return list(actions)

        user_message = _event_user_message(event_payload)
        if not user_message:
            return list(actions)
        if _is_abusive_noise(user_message) and any(action.type == ActionType.SEND_MESSAGE for action in actions):
            return list(actions)
        if _is_explicit_page_explanation_request(user_message):
            return list(actions)
        if _has_quiz_flow_action(actions):
            return list(actions)
        if _is_clear_quiz_request_message(user_message):
            metadata = _quiz_request_metadata_from_message(user_message)
            if metadata:
                state.pending_quiz_request = metadata
            quiz_type = _infer_quiz_type_from_message(user_message)
            tool = _quiz_generation_tool(quiz_type)
            warnings.append(self._warning(
                "USER_MESSAGE_ROUTED_TO_QUIZ",
                "Quiz-like USER_MESSAGE was constrained to the quiz flow instead of ANSWER_QUESTION.",
                tool=tool.value if tool else None,
            ))
            if tool:
                return [OrchestratorAction(
                    type=ActionType.CALL_TOOL,
                    tool=tool,
                    params={"quiz_type": quiz_type, **metadata},
                )]
            return [OrchestratorAction(
                type=ActionType.SET_UI_STATE,
                ui_state={"modal": "QUIZ_TYPE_PICKER", "reason": "USER_QUIZ_REQUEST"},
            )]
        if _is_quiz_check_request_message(user_message):
            metadata = _quiz_request_metadata_from_message(user_message)
            if metadata:
                state.pending_quiz_request = metadata
            warnings.append(self._warning(
                "USER_MESSAGE_ROUTED_TO_QUIZ_DECISION",
                "Quiz-check-like USER_MESSAGE was constrained to a quiz decision prompt.",
            ))
            return [OrchestratorAction(
                type=ActionType.SEND_MESSAGE,
                message="퀴즈로 이해도를 확인해볼까요?",
                ui_state={"widget": "QUIZ_DECISION", "reason": "USER_QUIZ_CHECK_REQUEST"},
            )]

        existing_answer = next(
            (
                action for action in actions
                if action.type == ActionType.CALL_TOOL and action.tool == ToolName.ANSWER_QUESTION
            ),
            None,
        )
        params = dict(existing_answer.params if existing_answer else {})
        params["question"] = params.get("question") or user_message

        warnings.append(self._warning(
            "USER_MESSAGE_ROUTED_TO_QA",
            "General USER_MESSAGE was constrained to ANSWER_QUESTION to avoid repeating page explanation.",
            tool=ToolName.ANSWER_QUESTION.value,
        ))
        return [OrchestratorAction(
            type=ActionType.CALL_TOOL,
            tool=ToolName.ANSWER_QUESTION,
            params=params,
        )]

    def _patch_event_followup_widgets(
        self,
        actions: list[OrchestratorAction],
        state: SessionState,
        *,
        event_type: str | None,
        event_payload: dict[str, Any],
        warnings: list[dict[str, Any]],
    ) -> list[OrchestratorAction]:
        if event_type in {
            AppEventType.PAGE_CHANGED.value,
            AppEventType.START_EXPLANATION_DECISION.value,
            AppEventType.NEXT_PAGE_DECISION.value,
        }:
            default_widget = _followup_widget_after_explanation(state, event_type, event_payload)
        else:
            return list(actions)

        patched: list[OrchestratorAction] = []
        for index, action in enumerate(actions):
            if action.type != ActionType.CALL_TOOL or action.tool != ToolName.EXPLAIN_PAGE:
                patched.append(action)
                continue
            params = dict(action.params or {})
            if params.get("next_widget") == default_widget:
                patched.append(action)
                continue
            params["next_widget"] = default_widget
            patched.append(action.model_copy(update={"params": params}))
            warnings.append(self._warning(
                "EXPLAIN_PAGE_FOLLOWUP_WIDGET_PATCHED",
                "EXPLAIN_PAGE action was patched with the expected follow-up widget for this event.",
                action_index=index,
                tool=ToolName.EXPLAIN_PAGE.value,
            ))
        return patched

    def _patch_decision_send_message_widgets(
        self,
        actions: list[OrchestratorAction],
        *,
        warnings: list[dict[str, Any]],
    ) -> list[OrchestratorAction]:
        patched: list[OrchestratorAction] = []
        for index, action in enumerate(actions):
            if action.type != ActionType.SEND_MESSAGE or action.ui_state:
                patched.append(action)
                continue

            widget = _decision_widget_from_message(action.message or "")
            if not widget:
                patched.append(action)
                continue

            patched.append(action.model_copy(update={"ui_state": {"widget": widget}}))
            warnings.append(self._warning(
                "DECISION_MESSAGE_WIDGET_PATCHED",
                "Decision prompt SEND_MESSAGE was patched with the matching UI widget.",
                action_index=index,
            ))
        return patched

    @staticmethod
    def _warning(
        code: str,
        message: str,
        *,
        action_index: int | None = None,
        tool: str | None = None,
    ) -> dict[str, Any]:
        out: dict[str, Any] = {"code": code, "message": message}
        if action_index is not None:
            out["actionIndex"] = action_index
        if tool:
            out["tool"] = tool
        return out


def _event_user_message(event_payload: dict[str, Any]) -> str:
    for key in ("question", "text", "message", "content", "userMessage", "prompt"):
        value = event_payload.get(key)
        if value is None:
            continue
        text = str(value).strip()
        if text:
            return text
    return ""


def _has_quiz_flow_action(actions: list[OrchestratorAction]) -> bool:
    for action in actions:
        if action.type == ActionType.CALL_TOOL and action.tool in _QUIZ_FLOW_TOOLS:
            return True
        if action.type == ActionType.SET_UI_STATE:
            ui_state = action.ui_state or {}
            if ui_state.get("modal") == "QUIZ_TYPE_PICKER":
                return True
    return False


def _is_explicit_page_explanation_request(message: str) -> bool:
    return bool(_PAGE_EXPLANATION_RE.search(message.strip()))


def _is_abusive_noise(message: str) -> bool:
    return bool(_ABUSIVE_RE.search(message) and not _LEARNING_SIGNAL_RE.search(message))


def _decision_widget_from_message(message: str) -> str | None:
    normalized = message.strip()
    if not normalized:
        return None
    if _RETEST_DECISION_MESSAGE_RE.search(normalized):
        return "RETEST_DECISION"
    if _QUIZ_DECISION_MESSAGE_RE.search(normalized):
        return "QUIZ_DECISION"
    if _NEXT_PAGE_DECISION_MESSAGE_RE.search(normalized):
        return "NEXT_PAGE_DECISION"
    return None


def followup_widget_after_explanation(
    state: SessionState,
    event_type: str | None,
    event_payload: dict[str, Any] | None = None,
) -> str:
    current_page = _event_page_number(event_payload or {}) or max(int(state.current_page or 1), 1)
    if _page_has_quiz_activity(state, current_page):
        return "NEXT_PAGE_DECISION"
    if not _is_quiz_worthy_page(state, current_page, event_payload or {}):
        return "NEXT_PAGE_DECISION"

    return "QUIZ_DECISION"


def _followup_widget_after_explanation(
    state: SessionState,
    event_type: str | None,
    event_payload: dict[str, Any] | None = None,
) -> str:
    return followup_widget_after_explanation(state, event_type, event_payload)


def _page_has_quiz_activity(state: SessionState, page_number: int) -> bool:
    if state.learner.quiz_attempt_counts.get(str(page_number), 0) > 0:
        return True
    return any(record.page_number == page_number for record in state.quiz_history)


def _is_quiz_worthy_page(
    state: SessionState,
    page_number: int,
    event_payload: dict[str, Any],
) -> bool:
    page_text = _page_text_for_quiz_policy(state, page_number, event_payload)
    if not page_text.strip():
        return True
    return _is_quiz_worthy_page_text(page_text)


def _page_text_for_quiz_policy(
    state: SessionState,
    page_number: int,
    event_payload: dict[str, Any],
) -> str:
    for key in ("pageText", "currentPageText", "pdfPageText"):
        value = event_payload.get(key)
        if isinstance(value, str) and value.strip():
            return value

    page_state = state.pages.get(page_number)
    pdf_path = None
    if page_state is not None:
        pdf_path = page_state.pdf_path
    pdf_path = pdf_path or state.pdf_path

    context = pdf_context_service.read_page_context(pdf_path, page_number)
    if context and context.has_page_text:
        return context.page_text

    if page_state and page_state.explanation:
        return page_state.explanation
    return ""


def _is_quiz_worthy_page_text(page_text: str) -> bool:
    normalized = re.sub(r"\s+", " ", page_text).strip()
    tokens = _PAGE_TOKEN_RE.findall(normalized)
    if len(tokens) < 12 or len(normalized) < 70:
        return False

    lines = [line.strip() for line in page_text.splitlines() if line.strip()]
    if _looks_like_cover_or_outline_page(normalized, lines, tokens):
        return False

    return bool(_QUIZ_WORTHY_SIGNAL_RE.search(normalized)) or len(tokens) >= 45


def _looks_like_cover_or_outline_page(
    normalized: str,
    lines: list[str],
    tokens: list[str],
) -> bool:
    has_non_quiz_marker = bool(_NON_QUIZ_PAGE_MARKER_RE.search(normalized))
    if has_non_quiz_marker and len(tokens) < 45:
        return True

    if not lines:
        return True

    explanatory_lines = [
        line for line in lines
        if len(_PAGE_TOKEN_RE.findall(line)) >= 8 and _EXPLANATORY_SENTENCE_RE.search(line)
    ]
    bulletish_lines = [
        line for line in lines
        if re.match(r"^(\d+[\).\s]|[IVX]+[\).\s]|[•▪□○\-\uf06f\uf0a7]+|chapter\s+\d+|[가-힣A-Za-z ]+\s*\d+$)", line, re.IGNORECASE)
    ]
    outline_marker = bool(re.search(r"(목차|차례|contents|overview|개요|학습\s*목표|roadmap)", normalized, re.IGNORECASE))
    mostly_outline = len(lines) >= 3 and len(bulletish_lines) >= max(2, len(lines) // 2)
    short_bullet_lines = [
        line for line in bulletish_lines
        if len(_PAGE_TOKEN_RE.findall(line)) <= 6
    ]
    short_outline = bool(bulletish_lines) and len(short_bullet_lines) / len(bulletish_lines) >= 0.7

    if (outline_marker or (mostly_outline and short_outline)) and len(explanatory_lines) <= 1:
        return True
    return False


def _is_quiz_request_message(message: str) -> bool:
    return _is_clear_quiz_request_message(message) or _is_quiz_check_request_message(message)


def _is_clear_quiz_request_message(message: str) -> bool:
    text = message.strip()
    return bool(_QUIZ_REQUEST_RE.search(text))


def _is_quiz_check_request_message(message: str) -> bool:
    text = message.strip()
    return bool(_QUIZ_CHECK_REQUEST_RE.search(text))


def _infer_quiz_type_from_message(message: str) -> str:
    text = message.strip()
    for pattern, quiz_type in _QUIZ_TYPE_PATTERNS:
        if pattern.search(text):
            return quiz_type
    return ""


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


def _infer_quiz_count_from_message(message: str) -> int | None:
    match = _QUIZ_COUNT_RE.search(message.strip())
    if not match:
        return None
    count = int(match.group(1))
    return max(1, min(count, 20))


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
    for pattern in _SINGLE_PAGE_QUIZ_PATTERNS:
        match = pattern.search(text)
        if not match:
            continue
        page = _first_positive_int(match.groups())
        if page is not None:
            return (page, page)
    return None


def _first_positive_int(values: tuple[object, ...]) -> int | None:
    for value in values:
        try:
            parsed = int(str(value))
        except (TypeError, ValueError):
            continue
        if parsed >= 1:
            return parsed
    return None


def _quiz_generation_tool(quiz_type: str) -> ToolName | None:
    normalized = normalize_exam_type_string(quiz_type)
    if normalized == "Five_Choice":
        return ToolName.GENERATE_QUIZ_FIVE_CHOICE
    if normalized == "OX_Problem":
        return ToolName.GENERATE_QUIZ_OX
    if normalized == "Short_Answer":
        return ToolName.GENERATE_QUIZ_SHORT
    if normalized == "Essay":
        return ToolName.GENERATE_QUIZ_ESSAY
    if normalized == "Flash_Card":
        return ToolName.GENERATE_QUIZ_FLASH
    return None


def _event_page_number(payload: dict[str, Any]) -> int | None:
    for key in ("page", "pageNumber", "page_number", "currentPage", "current_page", "targetPage", "target_page"):
        raw = payload.get(key)
        if raw is None:
            continue
        try:
            page = int(raw)
        except (TypeError, ValueError):
            continue
        if page >= 1:
            return page
    from_page = payload.get("fromPage", payload.get("from_page"))
    try:
        page = int(from_page) + 1
    except (TypeError, ValueError):
        return None
    return page if page >= 1 else None
