from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Any


ALLOWED_EXAM_STUDIO_OPERATIONS = {
    "patchExamSettings",
    "appendQuestions",
    "replaceQuestion",
}

MAX_EXAM_STUDIO_OPERATIONS = 8
MAX_QUESTIONS_PER_APPEND = 50
MIN_QUESTION_POINTS = 0.5
MAX_QUESTION_POINTS = 100.0


@dataclass(frozen=True)
class ExamStudioOperationValidationResult:
    operations: list[dict[str, Any]]
    warnings: list[str]


def validate_exam_studio_operations(raw_operations: Any) -> ExamStudioOperationValidationResult:
    warnings: list[str] = []
    if not isinstance(raw_operations, list):
        return ExamStudioOperationValidationResult([], ["operations_not_list"])

    sanitized: list[dict[str, Any]] = []
    for raw in raw_operations[:12]:
        if not isinstance(raw, dict):
            warnings.append("operation_not_object")
            continue
        method = raw.get("method")
        params = raw.get("params")
        if method not in ALLOWED_EXAM_STUDIO_OPERATIONS:
            warnings.append(f"unsupported_operation:{method}")
            continue
        if not isinstance(params, dict) or not params:
            warnings.append(f"empty_params:{method}")
            continue

        if method == "patchExamSettings":
            cleaned = _sanitize_settings_patch(params, warnings)
            if not cleaned:
                warnings.append("empty_params_after_sanitize:patchExamSettings")
                continue
            sanitized.append({"method": method, "params": cleaned})
            continue

        if method == "appendQuestions":
            questions = params.get("questions")
            if not isinstance(questions, list) or not questions:
                warnings.append("append_questions_empty")
                continue
            sanitized_questions = _sanitize_questions(questions, warnings)
            if not sanitized_questions:
                warnings.append("append_questions_invalid")
                continue
            sanitized.append({
                "method": method,
                "params": {"questions": sanitized_questions[:MAX_QUESTIONS_PER_APPEND]},
            })
            continue

        if method == "replaceQuestion":
            if not params.get("replaceQuestionId") or not isinstance(params.get("question"), dict):
                warnings.append("replace_question_invalid")
                continue
            question = _sanitize_question(params["question"], warnings)
            if not question:
                warnings.append("replace_question_invalid")
                continue
            cleaned = dict(params)
            cleaned["question"] = question
            sanitized.append({"method": method, "params": cleaned})

    if len(sanitized) > MAX_EXAM_STUDIO_OPERATIONS:
        warnings.append("operation_hard_cap_trimmed")

    return ExamStudioOperationValidationResult(
        sanitized[:MAX_EXAM_STUDIO_OPERATIONS],
        warnings,
    )


def _sanitize_settings_patch(params: dict[str, Any], warnings: list[str]) -> dict[str, Any]:
    cleaned = dict(params)
    for key in ("availableFrom", "availableUntil"):
        if key in cleaned and not _is_valid_iso(cleaned[key]):
            cleaned.pop(key, None)
            warnings.append(f"invalid_iso:{key}")
    return cleaned


def _sanitize_questions(questions: list[Any], warnings: list[str]) -> list[dict[str, Any]]:
    sanitized: list[dict[str, Any]] = []
    for raw in questions[:MAX_QUESTIONS_PER_APPEND]:
        if not isinstance(raw, dict):
            warnings.append("question_not_object")
            continue
        question = _sanitize_question(raw, warnings)
        if question:
            sanitized.append(question)
    if len(questions) > MAX_QUESTIONS_PER_APPEND:
        warnings.append("question_hard_cap_trimmed")
    return sanitized


def _sanitize_question(raw: dict[str, Any], warnings: list[str]) -> dict[str, Any]:
    question = dict(raw)
    prompt = question.get("prompt") or question.get("question") or question.get("questionText")
    if not isinstance(prompt, str) or not prompt.strip():
        warnings.append("question_prompt_missing")
        return {}
    question["prompt"] = prompt.strip()

    qtype = str(question.get("type") or question.get("questionType") or "").strip().upper()
    if qtype in {"O/X", "TRUE_FALSE", "TRUEFALSE"}:
        qtype = "OX"
    if qtype not in {"MCQ", "OX", "SHORT", "ESSAY"}:
        qtype = "SHORT"
        warnings.append("question_type_defaulted")
    question["type"] = qtype

    points = _coerce_points(question.get("points", 1), warnings)
    question["points"] = points

    if qtype == "MCQ":
        choices = question.get("choices")
        valid_choices = [item for item in choices if isinstance(item, dict)] if isinstance(choices, list) else []
        if len(valid_choices) < 2:
            warnings.append("mcq_choices_invalid")
            return {}
        question["choices"] = valid_choices
        answer = question.get("answer")
        answer_choice_id = str(answer.get("choiceId")) if isinstance(answer, dict) and answer.get("choiceId") is not None else ""
        choice_ids = {str(choice.get("id")) for choice in valid_choices if choice.get("id") is not None}
        if not answer_choice_id:
            warnings.append("mcq_answer_missing")
            return {}
        if choice_ids and answer_choice_id not in choice_ids:
            warnings.append("mcq_answer_choice_missing")
            return {}
    elif qtype == "OX":
        answer = question.get("answer")
        normalized = _normalize_ox_answer(answer.get("value") if isinstance(answer, dict) else None)
        if not normalized:
            warnings.append("ox_answer_missing")
            return {}
        question["answer"] = {"value": normalized}
    elif qtype == "SHORT" and not (question.get("referenceAnswer") or question.get("rubricMarkdown")):
        warnings.append("short_reference_missing")
        return {}
    elif qtype == "ESSAY" and not question.get("rubricMarkdown"):
        warnings.append("essay_rubric_missing")
        return {}

    return question


def _coerce_points(value: Any, warnings: list[str]) -> float:
    try:
        points = float(value)
    except (TypeError, ValueError):
        warnings.append("question_points_defaulted")
        return 1.0
    if points < MIN_QUESTION_POINTS:
        warnings.append("question_points_clamped")
        return MIN_QUESTION_POINTS
    if points > MAX_QUESTION_POINTS:
        warnings.append("question_points_clamped")
        return MAX_QUESTION_POINTS
    return points


def _normalize_ox_answer(value: Any) -> str:
    text = str(value or "").strip().upper()
    if text in {"O", "TRUE", "T", "YES", "Y", "참", "맞음"}:
        return "O"
    if text in {"X", "FALSE", "F", "NO", "N", "거짓", "틀림"}:
        return "X"
    return ""


def _is_valid_iso(value: Any) -> bool:
    if not isinstance(value, str) or not value.strip():
        return False
    try:
        datetime.fromisoformat(value.replace("Z", "+00:00"))
        return True
    except ValueError:
        return False
