from __future__ import annotations

import os
from typing import Any

from ai_agent.types.domain import (
    NdjsonEvent,
    NdjsonEventType,
    OrchestratorAction,
    OrchestratorPlan,
    SessionState,
)

_TRUE_VALUES = {"1", "true", "yes", "on"}
_TEXT_KEYS = {"text", "question", "message", "student_message", "source_request", "sourceRequest"}
_SAFE_VALUE_KEYS = {
    "accept",
    "count",
    "coverage_end_page",
    "coverage_start_page",
    "coverageEndPage",
    "coverageStartPage",
    "decision",
    "detail",
    "examType",
    "exam_type",
    "mode",
    "next_widget",
    "page",
    "page_number",
    "quizType",
    "quiz_type",
    "selectedType",
}


def thought_trace_enabled() -> bool:
    value = (
        os.getenv("AI_SERVICE_TRACE_THOUGHTS")
        or os.getenv("AI_TRACE_THOUGHTS")
        or ""
    )
    return value.strip().lower() in _TRUE_VALUES


def trace_event(
    message: str,
    *,
    agent: str = "orchestrator",
    tool: str | None = None,
) -> NdjsonEvent | None:
    if not thought_trace_enabled():
        return None
    return NdjsonEvent(
        type=NdjsonEventType.AGENT_DELTA,
        agent=agent,
        tool=tool,
        channel="thought",
        delta=_normalize_trace_text(message),
    )


def summarize_state(state: SessionState) -> str:
    latest_quiz = None
    for record in reversed(state.quiz_history):
        latest_quiz = record
        break
    latest_quiz_text = (
        f"{latest_quiz.quiz_type}/page={latest_quiz.page_number}/questions={len(latest_quiz.questions)}"
        if latest_quiz
        else "none"
    )
    return (
        f"session={state.session_id}, lecture={state.lecture_id}, page={state.current_page}, "
        f"pdf={'set' if state.pdf_path else 'none'}, activeIntervention={'yes' if state.active_intervention else 'no'}, "
        f"latestQuiz={latest_quiz_text}, pendingAssessments={_pending_assessment_count(state)}"
    )


def summarize_payload(payload: dict[str, Any] | None) -> str:
    if not payload:
        return "{}"
    parts: list[str] = []
    for key in sorted(payload.keys()):
        value = payload[key]
        if key in _TEXT_KEYS:
            parts.append(f"{key}={_short(value)}")
        elif key == "answers" and isinstance(value, list):
            parts.append(f"answers=count:{len(value)}")
        elif key in _SAFE_VALUE_KEYS:
            parts.append(f"{key}={_short(value)}")
    return "{" + ", ".join(parts) + "}" if parts else "{keys=" + ",".join(sorted(payload.keys())) + "}"


def summarize_plan(plan: OrchestratorPlan | None) -> str:
    if plan is None:
        return "plan=none"
    actions = "; ".join(summarize_action(action) for action in plan.actions) or "no-actions"
    policy = plan.pedagogy_policy
    warnings = len(plan.verification_warnings or [])
    return (
        f"actions={len(plan.actions)} [{actions}], "
        f"policy={policy.mode.value}, allowDirect={policy.allow_direct_answer}, "
        f"hintDepth={policy.hint_depth}, budget={policy.intervention_budget}, warnings={warnings}"
    )


def summarize_action(action: OrchestratorAction) -> str:
    if action.type.value == "CALL_TOOL":
        tool = action.tool.value if action.tool else "none"
        return f"CALL_TOOL:{tool}({summarize_params(action.params)})"
    if action.type.value == "SET_UI_STATE":
        return f"SET_UI_STATE({summarize_params(action.ui_state)})"
    return f"SEND_MESSAGE({summarize_params(action.ui_state)}, text={_short(action.message)})"


def summarize_params(params: dict[str, Any] | None) -> str:
    if not params:
        return ""
    parts: list[str] = []
    for key in sorted(params.keys()):
        value = params[key]
        if key in _TEXT_KEYS:
            parts.append(f"{key}={_short(value)}")
        elif key in _SAFE_VALUE_KEYS:
            parts.append(f"{key}={_short(value)}")
        elif key == "answers" and isinstance(value, list):
            parts.append(f"answers=count:{len(value)}")
        elif isinstance(value, (str, int, float, bool)) and len(parts) < 4:
            parts.append(f"{key}={_short(value)}")
    if not parts:
        return "keys=" + ",".join(sorted(params.keys()))
    return ", ".join(parts)


def summarize_context(
    *,
    page_number: int,
    page_count: int = 0,
    page_text: str = "",
    prev_text: str = "",
    next_text: str = "",
    related_pages_digest: str = "",
    qa_thread_digest: str = "",
    coverage_start_page: int | None = None,
    coverage_end_page: int | None = None,
) -> str:
    scope = (
        f"{coverage_start_page}-{coverage_end_page}"
        if coverage_start_page and coverage_end_page
        else str(page_number)
    )
    return (
        f"page={page_number}"
        + (f"/{page_count}" if page_count else "")
        + f", scope={scope}, pageChars={len(page_text or '')}, "
        f"prevChars={len(prev_text or '')}, nextChars={len(next_text or '')}, "
        f"related={'yes' if (related_pages_digest or '').strip() else 'no'}, "
        f"qaThread={'yes' if (qa_thread_digest or '').strip() else 'no'}"
    )


def summarize_warnings(warnings: list[dict[str, Any]] | None) -> str:
    if not warnings:
        return "none"
    codes = [str(w.get("code") or w.get("type") or "UNKNOWN") for w in warnings[-5:]]
    return ", ".join(codes)


def _pending_assessment_count(state: SessionState) -> int:
    return sum(1 for item in state.quiz_assessments if str(item.get("status") or "").upper() == "PENDING")


def _short(value: Any, limit: int = 90) -> str:
    text = "" if value is None else str(value)
    text = " ".join(text.split())
    if len(text) <= limit:
        return repr(text)
    return repr(text[:limit].rstrip() + "...")


def _normalize_trace_text(message: str) -> str:
    text = message.strip()
    return text + "\n" if text else ""
