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
            event_type=event_type,
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
        *,
        event_type: str | None,
        warnings: list[dict[str, Any]],
    ) -> list[OrchestratorAction]:
        if event_type == AppEventType.PAGE_CHANGED.value:
            default_widget = "QUIZ_DECISION"
        elif event_type in {
            AppEventType.START_EXPLANATION_DECISION.value,
            AppEventType.NEXT_PAGE_DECISION.value,
        }:
            default_widget = "NEXT_PAGE_DECISION"
        else:
            return list(actions)

        patched: list[OrchestratorAction] = []
        for index, action in enumerate(actions):
            if action.type != ActionType.CALL_TOOL or action.tool != ToolName.EXPLAIN_PAGE:
                patched.append(action)
                continue
            params = dict(action.params or {})
            if params.get("next_widget"):
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
