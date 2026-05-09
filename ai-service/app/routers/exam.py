from __future__ import annotations

import json
from datetime import datetime
from typing import Any, AsyncIterator, Literal

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

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


def _is_valid_iso(value: Any) -> bool:
    if not isinstance(value, str) or not value.strip():
        return False
    try:
        datetime.fromisoformat(value.replace("Z", "+00:00"))
        return True
    except ValueError:
        return False


def _sanitize_studio_operations(raw_operations: Any) -> tuple[list[ExamStudioOperation], list[str]]:
    warnings: list[str] = []
    if not isinstance(raw_operations, list):
        return [], ["operations_not_list"]

    sanitized: list[ExamStudioOperation] = []
    for raw in raw_operations[:12]:
        if not isinstance(raw, dict):
            warnings.append("operation_not_object")
            continue
        method = raw.get("method")
        params = raw.get("params")
        if method not in {"patchExamSettings", "appendQuestions", "replaceQuestion"}:
            warnings.append(f"unsupported_operation:{method}")
            continue
        if not isinstance(params, dict) or not params:
            warnings.append(f"empty_params:{method}")
            continue

        if method == "patchExamSettings":
            cleaned = dict(params)
            for key in ("availableFrom", "availableUntil"):
                if key in cleaned and not _is_valid_iso(cleaned[key]):
                    cleaned.pop(key, None)
                    warnings.append(f"invalid_iso:{key}")
            if not cleaned:
                warnings.append("empty_params_after_sanitize:patchExamSettings")
                continue
            sanitized.append(ExamStudioOperation(method=method, params=cleaned))
            continue

        if method == "appendQuestions":
            questions = params.get("questions")
            if not isinstance(questions, list) or not questions:
                warnings.append("append_questions_empty")
                continue
            sanitized.append(ExamStudioOperation(method=method, params={"questions": questions[:50]}))
            continue

        if method == "replaceQuestion":
            if not params.get("replaceQuestionId") or not isinstance(params.get("question"), dict):
                warnings.append("replace_question_invalid")
                continue
            sanitized.append(ExamStudioOperation(method=method, params=params))

    return sanitized[:8], warnings


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
- 교사가 문항 추가를 요청하면 appendQuestions를 사용한다.
- 교사가 기존 문항 수정을 요청하면 replaceQuestion을 사용한다.
- 수정할 것이 없으면 operations는 빈 배열 []이다.
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


async def run_exam_studio_chat(request: ExamStudioChatRequest) -> ExamStudioChatResponse:
    try:
        parsed = await _call_gemini_json(
            prompt=_build_exam_studio_prompt(request),
            model=_model_name(request.model),
            response_json_schema=request.responseJsonSchema,
        )
        operations, warnings = _sanitize_studio_operations(parsed.get("operations", []))
        answer = parsed.get("answerMarkdown")
        if not isinstance(answer, str) or not answer.strip():
            answer = "요청을 반영할 수 있는 변경안을 검토했습니다."
        return ExamStudioChatResponse(
            answerMarkdown=answer.strip(),
            operations=operations,
            source="AI",
            fallbackUsed=False,
            reason=None,
            confidence="MEDIUM",
            warnings=warnings,
        )
    except Exception as exc:  # noqa: BLE001
        return _fallback_studio_response(request, f"ai_fallback:{type(exc).__name__}")


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
        correct = comparable and str(expected).strip().lower() == str(actual).strip().lower()
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
            response_json_schema=request.responseJsonSchema,
        )
        return ExamGradeResponse.model_validate(_normalize_grade_payload(parsed, request))
    except Exception as exc:  # noqa: BLE001
        return _fallback_grade(request, f"ai_fallback:{type(exc).__name__}")


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
                "details": {"errorType": type(exc).__name__},
            })

    return StreamingResponse(events(), media_type="application/x-ndjson")


@router.post("/grade", response_model=ExamGradeResponse)
async def exam_grade(request: ExamGradeRequest) -> ExamGradeResponse:
    return await run_exam_grade(request)
