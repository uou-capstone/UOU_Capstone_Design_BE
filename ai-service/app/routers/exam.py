from __future__ import annotations

import json
import re
from typing import Any, AsyncIterator, Literal

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from app.services.error_mapping import stable_error_type
from app.services.exam_studio_operation_validator import validate_exam_studio_operations
from app.services.gemini_service import generate_json, model_name

router = APIRouter(prefix="/api/v3/exam", tags=["v3-exam"])


class ExamStudioChatRequest(BaseModel):
    model: str | None = None
    message: str
    currentDraft: dict[str, Any] = Field(default_factory=dict)
    currentKstIso: str | None = None
    timeZone: str | None = None
    sourceText: str | None = None
    responseJsonSchema: dict[str, Any] | None = None


class ExamStudioOperation(BaseModel):
    method: Literal["patchExamSettings", "appendQuestions", "replaceQuestion"]
    params: dict[str, Any] = Field(default_factory=dict)


class ExamStudioChatResponse(BaseModel):
    answerMarkdown: str
    operations: list[ExamStudioOperation] = Field(default_factory=list)
    source: str = "AI"
    fallbackUsed: bool = False
    reason: str | None = None
    confidence: Literal["LOW", "MEDIUM", "HIGH"] = "MEDIUM"
    warnings: list[str] = Field(default_factory=list)


class ExamGradeRequest(BaseModel):
    model: str | None = None
    exam: dict[str, Any]
    answers: dict[str, Any] = Field(default_factory=dict)
    responseJsonSchema: dict[str, Any] | None = None


class ExamGradeItem(BaseModel):
    questionId: str
    score: float
    maxScore: float
    verdict: Literal["CORRECT", "WRONG", "PARTIAL"]
    feedbackMarkdown: str


class ExamGradeResponse(BaseModel):
    totalScore: float
    maxScore: float
    scoreRatio: float
    items: list[ExamGradeItem]
    summaryMarkdown: str
    gradingSource: str = "AI"
    fallbackUsed: bool = False
    reason: str | None = None
    confidence: Literal["LOW", "MEDIUM", "HIGH"] = "MEDIUM"
    warnings: list[str] = Field(default_factory=list)


def _model_name(requested: str | None) -> str:
    return model_name(requested)


def _api_key() -> str:
    from app.services.gemini_service import api_key

    return api_key()


def _ndjson(payload: dict[str, Any]) -> bytes:
    return (json.dumps(payload, ensure_ascii=False) + "\n").encode("utf-8")


async def _call_gemini_json(
    *,
    prompt: str,
    model: str,
    response_json_schema: dict[str, Any] | None = None,
) -> dict[str, Any]:
    return await generate_json(
        prompt=prompt,
        model=model,
        response_json_schema=response_json_schema,
    )


def _exam_studio_response_schema() -> dict[str, Any]:
    return {
        "type": "object",
        "properties": {
            "answerMarkdown": {"type": "string"},
            "operations": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "method": {
                            "type": "string",
                            "enum": ["patchExamSettings", "appendQuestions", "replaceQuestion"],
                        },
                        "params": {"type": "object"},
                    },
                    "required": ["method", "params"],
                },
            },
            "source": {"type": "string"},
        },
        "required": ["answerMarkdown", "operations"],
    }


def _exam_grade_response_schema() -> dict[str, Any]:
    return {
        "type": "object",
        "properties": {
            "totalScore": {"type": "number"},
            "maxScore": {"type": "number"},
            "scoreRatio": {"type": "number"},
            "items": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "questionId": {"type": "string"},
                        "score": {"type": "number"},
                        "maxScore": {"type": "number"},
                        "verdict": {"type": "string", "enum": ["CORRECT", "WRONG", "PARTIAL"]},
                        "feedbackMarkdown": {"type": "string"},
                    },
                    "required": ["questionId", "score", "maxScore", "verdict", "feedbackMarkdown"],
                },
            },
            "summaryMarkdown": {"type": "string"},
            "gradingSource": {"type": "string"},
        },
        "required": ["totalScore", "maxScore", "scoreRatio", "items", "summaryMarkdown"],
    }


def _sanitize_studio_operations(raw_operations: Any) -> tuple[list[ExamStudioOperation], list[str]]:
    result = validate_exam_studio_operations(raw_operations)
    return [ExamStudioOperation.model_validate(item) for item in result.operations], result.warnings


def _question_generation_requested(message: str) -> bool:
    lowered = message.lower()
    markers = (
        "문제",
        "문항",
        "퀴즈",
        "출제",
        "객관식",
        "선택형",
        "단답",
        "서술",
        "참거짓",
        "참/거짓",
        "ox",
        "o/x",
        "question",
        "quiz",
        "mcq",
        "multiple choice",
    )
    return any(marker in lowered for marker in markers)


def _exam_change_requested(message: str) -> bool:
    lowered = message.lower()
    markers = (
        "바꿔",
        "변경",
        "수정",
        "추가",
        "삭제",
        "만들",
        "생성",
        "출제",
        "설정",
        "시간",
        "제목",
        "마감",
        "시작",
        "replace",
        "append",
        "add",
        "change",
        "update",
        "create",
        "remove",
    )
    return any(marker in lowered for marker in markers)


def _requested_question_count(message: str, default: int = 2) -> int:
    digit_match = re.search(r"(\d+)\s*(?:개|문항|문제|questions?)", message, flags=re.IGNORECASE)
    if digit_match:
        return max(1, min(int(digit_match.group(1)), 10))

    korean_numbers = {
        "한": 1,
        "하나": 1,
        "두": 2,
        "둘": 2,
        "세": 3,
        "셋": 3,
        "네": 4,
        "넷": 4,
        "다섯": 5,
        "여섯": 6,
        "일곱": 7,
        "여덟": 8,
        "아홉": 9,
        "열": 10,
    }
    for word, value in korean_numbers.items():
        if re.search(rf"{word}\s*(?:개|문항|문제)", message):
            return value
    return default


def _requested_question_type(message: str) -> str:
    lowered = message.lower()
    if "ox" in lowered or "o/x" in lowered or "참거짓" in message or "참/거짓" in message:
        return "OX"
    if "객관식" in message or "선택형" in message or "mcq" in lowered or "multiple" in lowered:
        return "MCQ"
    if "서술" in message or "essay" in lowered:
        return "ESSAY"
    if "단답" in message or "short" in lowered:
        return "SHORT"
    return "OX"


def _source_sentences(source_text: str | None) -> list[str]:
    text = (source_text or "").strip()
    if not text:
        return []
    text = re.sub(r"(?im)^\s*\[page\s+\d+\]\s*$", " ", text)
    parts = re.split(r"(?<=[.!?。！？])\s+|[\n\r]+", text)
    sentences: list[str] = []
    for part in parts:
        cleaned = re.sub(r"\s+", " ", part).strip(" -\t")
        if len(cleaned) < 12:
            continue
        if re.fullmatch(r"\[?page\s+\d+\]?", cleaned, flags=re.IGNORECASE):
            continue
        sentences.append(cleaned)
        if len(sentences) >= 10:
            break
    return sentences


def _has_generation_context(request: ExamStudioChatRequest) -> bool:
    return bool((request.sourceText or "").strip())


def _missing_generation_context_response() -> ExamStudioChatResponse:
    return ExamStudioChatResponse(
        answerMarkdown="시험 문항 생성을 위해 강의자료 또는 PDF 컨텍스트가 필요합니다. 먼저 자료를 연결한 뒤 다시 요청해 주세요.",
        operations=[],
        source="FALLBACK",
        fallbackUsed=True,
        reason="MISSING_CONTEXT",
        confidence="LOW",
        warnings=["MISSING_CONTEXT"],
    )


def _make_ox_questions(message: str, source_text: str | None, count: int) -> list[dict[str, Any]]:
    sentences = _source_sentences(source_text)
    if not sentences:
        return []
    base = sentences
    questions: list[dict[str, Any]] = []
    for index in range(count):
        source_sentence = base[index % len(base)]
        is_true = index % 2 == 0
        if is_true:
            prompt = source_sentence.rstrip(".。") + "."
            answer = "O"
        else:
            prompt = "위 설명과 달리, 두 개념은 항상 같은 방식으로 일어난다."
            answer = "X"
        questions.append({
            "id": f"generated-ox-{index + 1}",
            "type": "OX",
            "prompt": prompt,
            "points": 1,
            "answer": {"value": answer},
            "explanationMarkdown": "제공된 수업 자료의 핵심 문장을 기준으로 판단합니다.",
        })
    return questions


def _make_mcq_questions(message: str, source_text: str | None, count: int) -> list[dict[str, Any]]:
    sentences = _source_sentences(source_text)
    if not sentences:
        return []
    topic = sentences[0]
    questions: list[dict[str, Any]] = []
    for index in range(count):
        questions.append({
            "id": f"generated-mcq-{index + 1}",
            "type": "MCQ",
            "prompt": f"다음 중 수업 내용과 가장 관련 있는 설명은 무엇인가요? ({index + 1})",
            "choices": [
                {"id": "A", "text": topic[:120]},
                {"id": "B", "text": "자료와 관계없는 설명"},
                {"id": "C", "text": "항상 반대로 생각하면 된다는 설명"},
                {"id": "D", "text": "근거 없이 외우기만 하면 된다는 설명"},
            ],
            "points": 1,
            "answer": {"choiceId": "A"},
            "explanationMarkdown": "제공된 수업 자료와 직접 연결되는 선택지를 고릅니다.",
        })
    return questions


def _make_short_questions(message: str, source_text: str | None, count: int) -> list[dict[str, Any]]:
    sentences = _source_sentences(source_text)
    if not sentences:
        return []
    topic = sentences[0]
    return [
        {
            "id": f"generated-short-{index + 1}",
            "type": "SHORT",
            "prompt": f"수업 자료를 바탕으로 핵심 개념을 한 문장으로 설명하세요. ({index + 1})",
            "points": 2,
            "referenceAnswer": {"text": topic[:160]},
            "rubricMarkdown": "- 핵심 용어를 포함한다.\n- 자료의 설명과 모순되지 않는다.",
        }
        for index in range(count)
    ]


def _make_essay_questions(message: str, source_text: str | None, count: int) -> list[dict[str, Any]]:
    if not _source_sentences(source_text):
        return []
    return [
        {
            "id": f"generated-essay-{index + 1}",
            "type": "ESSAY",
            "prompt": f"수업 자료의 핵심 개념을 예시와 함께 설명하세요. ({index + 1})",
            "points": 5,
            "rubricMarkdown": "- 핵심 개념을 정확히 설명한다.\n- 예시를 포함한다.\n- 오개념 없이 논리적으로 작성한다.",
        }
        for index in range(count)
    ]


def _make_fallback_questions(request: ExamStudioChatRequest) -> list[dict[str, Any]]:
    count = _requested_question_count(request.message)
    question_type = _requested_question_type(request.message)
    if question_type == "MCQ":
        return _make_mcq_questions(request.message, request.sourceText, count)
    if question_type == "SHORT":
        return _make_short_questions(request.message, request.sourceText, count)
    if question_type == "ESSAY":
        return _make_essay_questions(request.message, request.sourceText, count)
    return _make_ox_questions(request.message, request.sourceText, count)


def _repair_missing_question_generation(
    request: ExamStudioChatRequest,
    operations: list[ExamStudioOperation],
    warnings: list[str],
) -> tuple[list[ExamStudioOperation], list[str], bool]:
    if not _question_generation_requested(request.message):
        return operations, warnings, False
    if any(operation.method == "appendQuestions" for operation in operations):
        return operations, warnings, False
    if not _has_generation_context(request):
        return operations, [*warnings, "MISSING_CONTEXT"], False

    questions = _make_fallback_questions(request)
    if not questions:
        return operations, [*warnings, "MISSING_CONTEXT"], False
    repaired = list(operations)
    repaired.append(ExamStudioOperation(
        method="appendQuestions",
        params={"questions": questions},
    ))
    return repaired[:8], [*warnings, "empty_generation_operations_repaired"], True


def _build_exam_studio_prompt(request: ExamStudioChatRequest) -> str:
    return f"""
너는 교사용 LMS 시험 제작 스튜디오의 보조 에이전트다.
반드시 JSON만 출력하라. JSON 외 텍스트, 코드블록, 설명 문장을 절대 출력하지 마라.

출력 스키마:
{{
  "answerMarkdown": "교사에게 보여줄 짧은 한국어 Markdown 답변",
  "operations": [
    {{
      "method": "patchExamSettings",
      "params": {{
        "title": "선택",
        "description": "선택",
        "availableFrom": "ISO-8601 선택",
        "availableUntil": "ISO-8601 선택",
        "timeLimitMinutes": 30
      }}
    }},
    {{
      "method": "appendQuestions",
      "params": {{"questions": []}}
    }},
    {{
      "method": "replaceQuestion",
      "params": {{"replaceQuestionId": "기존 문항 id", "question": {{}}}}
    }}
  ],
  "source": "AI"
}}

규칙:
- 교사가 설정 변경을 요청하면 patchExamSettings를 사용한다.
- 교사가 문항 추가, 생성, 출제, 퀴즈 생성을 요청하면 반드시 appendQuestions를 사용하고 questions를 비워두지 않는다.
- 교사가 기존 문항 수정을 요청하면 replaceQuestion을 사용한다.
- operations는 사용자의 요청이 실제 변경을 요구하지 않을 때만 빈 배열 []이다.
- "문제 2개 만들어줘", "OX 문항 추가해줘", "객관식 생성" 같은 요청에서 operations=[]는 금지한다.
- top-level legacy 필드(settingsPatch, appendQuestions, replaceQuestionId)는 출력하지 않는다.
- 문제 유형은 MCQ, OX, SHORT, ESSAY 중 하나만 사용한다.
- MCQ는 choices와 answer.choiceId를 포함한다.
- OX는 answer.value를 포함한다.
- SHORT는 referenceAnswer.text 또는 rubricMarkdown을 포함한다.
- ESSAY는 rubricMarkdown을 반드시 포함한다.
- points는 0.5~100 사이로 둔다.
- 상대 시간 표현은 현재 시간과 time zone을 기준으로 ISO-8601 timezone 포함 문자열로 직접 계산한다.
- 시작 시각만 바꾸라는 요청이면 currentDraft.timeLimitMinutes를 유지하고 availableUntil도 함께 제안한다.
- 실제 params에 없는 변경을 answerMarkdown에서 변경했다고 말하지 않는다.
- 문항 생성 요청을 처리했으면 answerMarkdown에 추가할 문항 수와 유형을 짧게 말한다.

현재 시간:
{{
  "currentKstIso": "{request.currentKstIso or '(서버 기준 현재 시간 없음)'}",
  "timeZone": "{request.timeZone or 'Asia/Seoul'}"
}}

현재 draft:
{json.dumps(request.currentDraft, ensure_ascii=False)}

첨부/붙여넣기 자료:
{request.sourceText or "(자료 없음)"}

교사 메시지:
{request.message}
""".strip()


def _fallback_studio_response(request: ExamStudioChatRequest, reason: str | None = None) -> ExamStudioChatResponse:
    warnings = [reason] if reason else []
    return ExamStudioChatResponse(
        answerMarkdown="요청을 해석했지만 자동 변경안을 안정적으로 생성하지 못했습니다. 제목, 시간, 문항 추가처럼 구체적인 변경 내용을 다시 입력해 주세요.",
        operations=[],
        source="FALLBACK",
        fallbackUsed=True,
        reason=reason,
        confidence="LOW",
        warnings=warnings,
    )


def _invalid_studio_operation_response(
    request: ExamStudioChatRequest,
    warnings: list[str],
) -> ExamStudioChatResponse:
    reason = "MISSING_CONTEXT" if "MISSING_CONTEXT" in warnings else "VALIDATION_ERROR"
    message = (
        "요청은 변경 의도로 보이지만 실행 가능한 시험 변경안을 만들지 못했습니다. "
        "시험 제목, 시작/마감 시간, 문항 유형과 개수처럼 적용할 값을 더 구체적으로 입력해 주세요."
    )
    if reason == "MISSING_CONTEXT":
        message = "시험 문항 생성을 위해 사용할 수 있는 강의자료 context가 부족합니다. PDF context를 먼저 연결한 뒤 다시 요청해 주세요."
    return ExamStudioChatResponse(
        answerMarkdown=message,
        operations=[],
        source="FALLBACK",
        fallbackUsed=True,
        reason=reason,
        confidence="LOW",
        warnings=sorted({*(warnings or []), reason}),
    )


def _operation_summary(operations: list[ExamStudioOperation]) -> str:
    if not operations:
        return ""
    counts: dict[str, int] = {}
    question_count = 0
    for operation in operations:
        counts[operation.method] = counts.get(operation.method, 0) + 1
        if operation.method == "appendQuestions":
            questions = operation.params.get("questions")
            if isinstance(questions, list):
                question_count += len(questions)
    parts: list[str] = []
    if counts.get("patchExamSettings"):
        parts.append("시험 설정 변경")
    if question_count:
        parts.append(f"문항 {question_count}개 추가")
    if counts.get("replaceQuestion"):
        parts.append(f"문항 {counts['replaceQuestion']}개 교체")
    return ", ".join(parts)


async def run_exam_studio_chat(request: ExamStudioChatRequest) -> ExamStudioChatResponse:
    if _question_generation_requested(request.message) and not _has_generation_context(request):
        return _missing_generation_context_response()

    try:
        parsed = await _call_gemini_json(
            prompt=_build_exam_studio_prompt(request),
            model=_model_name(request.model),
            response_json_schema=request.responseJsonSchema or _exam_studio_response_schema(),
        )
        operations, warnings = _sanitize_studio_operations(parsed.get("operations", []))
        operations, warnings, repaired = _repair_missing_question_generation(request, operations, warnings)
        if _exam_change_requested(request.message) and not operations:
            return _invalid_studio_operation_response(request, warnings or ["empty_operations_for_change_request"])

        answer = parsed.get("answerMarkdown")
        if not isinstance(answer, str) or not answer.strip():
            answer = "요청을 반영할 수 있는 변경안을 검토했습니다."
        if repaired:
            question_count = len(operations[-1].params.get("questions", [])) if operations else 0
            question_type = _requested_question_type(request.message)
            answer = f"문항 생성 요청을 반영해 {question_type} 문항 {question_count}개 추가안을 만들었습니다."
        elif operations and "변경" not in answer and "추가" not in answer and "교체" not in answer:
            summary = _operation_summary(operations)
            if summary:
                answer = f"{answer.strip()}\n\n적용 예정 변경: {summary}."
        return ExamStudioChatResponse(
            answerMarkdown=answer.strip(),
            operations=operations,
            source="FALLBACK" if repaired else "AI",
            fallbackUsed=repaired,
            reason="VALIDATION_ERROR" if repaired else None,
            confidence="LOW" if repaired else "MEDIUM",
            warnings=warnings,
        )
    except Exception as exc:  # noqa: BLE001
        return _fallback_studio_response(request, stable_error_type(exc))


def _question_id(question: dict[str, Any], index: int) -> str:
    for key in ("id", "questionId", "problem_id", "problemId"):
        value = question.get(key)
        if value is not None:
            return str(value)
    return str(index + 1)


def _question_points(question: dict[str, Any]) -> float:
    value = question.get("points", question.get("score", question.get("maxScore", 1)))
    try:
        return max(float(value), 0.0)
    except (TypeError, ValueError):
        return 1.0


def _iter_exam_questions(exam: dict[str, Any]) -> list[dict[str, Any]]:
    questions = exam.get("questions")
    if isinstance(questions, list):
        return [q for q in questions if isinstance(q, dict)]
    sections = exam.get("sections")
    if isinstance(sections, list):
        out: list[dict[str, Any]] = []
        for section in sections:
            if isinstance(section, dict) and isinstance(section.get("questions"), list):
                out.extend(q for q in section["questions"] if isinstance(q, dict))
        return out
    return []


def _answer_for(answers: dict[str, Any], question_id: str, index: int) -> Any:
    for key in (question_id, str(index), str(index + 1)):
        if key in answers:
            return answers[key]
    if isinstance(answers.get("answers"), list):
        items = answers["answers"]
        if index < len(items):
            item = items[index]
            if isinstance(item, dict):
                return item.get("answer", item.get("value", item.get("userAnswer", item)))
            return item
    return None


def _expected_answer(question: dict[str, Any]) -> Any:
    answer = question.get("answer")
    if isinstance(answer, dict):
        for key in ("choiceId", "value", "text"):
            if key in answer:
                return answer[key]
    return answer or question.get("correctAnswer") or question.get("referenceAnswer")


def _extract_answer_value(value: Any) -> Any:
    if isinstance(value, dict):
        for key in (
            "answer",
            "value",
            "choiceId",
            "choice_id",
            "selectedChoiceId",
            "selected_choice_id",
            "userAnswer",
            "selected",
            "id",
            "text",
        ):
            if key in value and value[key] is not None:
                return _extract_answer_value(value[key])
    return value


def _normalize_grade_answer(value: Any) -> str:
    value = _extract_answer_value(value)
    if value is None:
        return ""
    if isinstance(value, bool):
        return "O" if value else "X"

    text = str(value).strip()
    compact = "".join(text.upper().split())
    aliases = {
        "TRUE": "O",
        "T": "O",
        "YES": "O",
        "Y": "O",
        "O": "O",
        "○": "O",
        "〇": "O",
        "참": "O",
        "맞음": "O",
        "FALSE": "X",
        "F": "X",
        "NO": "X",
        "N": "X",
        "X": "X",
        "×": "X",
        "거짓": "X",
        "틀림": "X",
    }
    return aliases.get(compact, compact)


def _fallback_grade(request: ExamGradeRequest, reason: str | None = None) -> ExamGradeResponse:
    items: list[ExamGradeItem] = []
    total = 0.0
    max_total = 0.0
    for index, question in enumerate(_iter_exam_questions(request.exam)):
        qid = _question_id(question, index)
        max_score = _question_points(question)
        expected = _expected_answer(question)
        actual = _answer_for(request.answers, qid, index)
        comparable = expected is not None and actual is not None
        correct = comparable and _normalize_grade_answer(expected) == _normalize_grade_answer(actual)
        score = max_score if correct else 0.0
        verdict: Literal["CORRECT", "WRONG", "PARTIAL"] = "CORRECT" if correct else "WRONG"
        items.append(ExamGradeItem(
            questionId=qid,
            score=score,
            maxScore=max_score,
            verdict=verdict,
            feedbackMarkdown="자동 비교 채점 결과입니다." if comparable else "AI 채점 실패로 수동 확인이 필요합니다.",
        ))
        total += score
        max_total += max_score

    warnings = [reason] if reason else []
    if not items:
        warnings.append("no_questions_found")
    ratio = total / max_total if max_total else 0.0
    return ExamGradeResponse(
        totalScore=round(total, 3),
        maxScore=round(max_total, 3),
        scoreRatio=round(ratio, 3),
        items=items,
        summaryMarkdown="AI 채점에 실패해 가능한 문항만 deterministic fallback으로 채점했습니다.",
        gradingSource="FALLBACK",
        fallbackUsed=True,
        reason=reason,
        confidence="LOW",
        warnings=warnings,
    )


def _build_grade_prompt(request: ExamGradeRequest) -> str:
    return f"""
다음 교사용 시험과 학생 답안을 채점하고 JSON만 출력하라.
점수는 각 문항 points 범위 안에서 엄격하게 매긴다.
객관식/OX는 정답 일치 여부를 우선한다.
단답형/서술형은 rubricMarkdown, modelAnswerMarkdown, referenceAnswer를 우선 기준으로 삼는다.

출력 스키마:
{{
  "totalScore": number,
  "maxScore": number,
  "scoreRatio": number,
  "items": [
    {{
      "questionId": "...",
      "score": number,
      "maxScore": number,
      "verdict": "CORRECT|WRONG|PARTIAL",
      "feedbackMarkdown": "..."
    }}
  ],
  "summaryMarkdown": "...",
  "gradingSource": "AI"
}}

시험:
{json.dumps(request.exam, ensure_ascii=False)}

학생 답안:
{json.dumps(request.answers, ensure_ascii=False)}
""".strip()


def _normalize_grade_payload(parsed: dict[str, Any], request: ExamGradeRequest) -> dict[str, Any]:
    questions = _iter_exam_questions(request.exam)
    items = parsed.get("items") if isinstance(parsed.get("items"), list) else []
    normalized: list[dict[str, Any]] = []
    for index, raw in enumerate(items):
        item = raw if isinstance(raw, dict) else {}
        question = questions[index] if index < len(questions) else {}
        max_score = item.get("maxScore", _question_points(question))
        score = item.get("score", 0)
        try:
            max_score = float(max_score)
        except (TypeError, ValueError):
            max_score = _question_points(question)
        try:
            score = float(score)
        except (TypeError, ValueError):
            score = 0.0
        verdict = item.get("verdict")
        if verdict not in {"CORRECT", "WRONG", "PARTIAL"}:
            verdict = "CORRECT" if score >= max_score and max_score > 0 else "WRONG"
        normalized.append({
            "questionId": str(item.get("questionId") or _question_id(question, index)),
            "score": max(0.0, min(score, max_score)),
            "maxScore": max_score,
            "verdict": verdict,
            "feedbackMarkdown": str(item.get("feedbackMarkdown") or "채점 피드백이 제공되지 않았습니다."),
        })

    if not normalized and questions:
        return _fallback_grade(request, "empty_ai_items").model_dump(mode="json")

    total = sum(item["score"] for item in normalized)
    max_total = sum(item["maxScore"] for item in normalized)
    parsed["items"] = normalized
    parsed["totalScore"] = float(parsed.get("totalScore", total) or total)
    parsed["maxScore"] = float(parsed.get("maxScore", max_total) or max_total)
    parsed["scoreRatio"] = float(parsed.get("scoreRatio", (parsed["totalScore"] / parsed["maxScore"] if parsed["maxScore"] else 0.0)) or 0.0)
    parsed["summaryMarkdown"] = str(parsed.get("summaryMarkdown") or "채점이 완료되었습니다.")
    parsed["gradingSource"] = str(parsed.get("gradingSource") or "AI")
    parsed.setdefault("fallbackUsed", False)
    parsed.setdefault("reason", None)
    parsed.setdefault("confidence", "MEDIUM")
    parsed.setdefault("warnings", [])
    return parsed


async def run_exam_grade(request: ExamGradeRequest) -> ExamGradeResponse:
    try:
        parsed = await _call_gemini_json(
            prompt=_build_grade_prompt(request),
            model=_model_name(request.model),
            response_json_schema=request.responseJsonSchema or _exam_grade_response_schema(),
        )
        return ExamGradeResponse.model_validate(_normalize_grade_payload(parsed, request))
    except Exception as exc:  # noqa: BLE001
        return _fallback_grade(request, stable_error_type(exc))


@router.post("/studio/chat", response_model=ExamStudioChatResponse)
async def exam_studio_chat(request: ExamStudioChatRequest) -> ExamStudioChatResponse:
    return await run_exam_studio_chat(request)


@router.post("/studio/chat/stream")
async def exam_studio_chat_stream(request: ExamStudioChatRequest) -> StreamingResponse:
    async def events() -> AsyncIterator[bytes]:
        try:
            yield _ndjson({"type": "agent_delta", "channel": "thought", "text": "시험 draft와 교사 요청을 분석하고 있습니다."})
            result = await run_exam_studio_chat(request)
            yield _ndjson({"type": "agent_delta", "channel": "main", "text": result.answerMarkdown})
            yield _ndjson({"type": "done", "data": result.model_dump(mode="json")})
        except Exception as exc:  # noqa: BLE001
            yield _ndjson({
                "type": "error",
                "code": "EXAM_STUDIO_CHAT_FAILED",
                "message": "시험 스튜디오 응답 생성 중 오류가 발생했습니다.",
                "details": {"errorType": stable_error_type(exc)},
            })

    return StreamingResponse(events(), media_type="application/x-ndjson")


@router.post("/grade", response_model=ExamGradeResponse)
async def exam_grade(request: ExamGradeRequest) -> ExamGradeResponse:
    return await run_exam_grade(request)
