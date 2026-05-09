from __future__ import annotations

import json
from typing import Any, AsyncIterator, Literal

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from app.services.gemini_service import generate_json, generate_text, model_name

router = APIRouter(prefix="/api/v3/report", tags=["v3-report"])


class AiCourseInfo(BaseModel):
    courseId: int | None = None
    courseName: str | None = None
    teacherId: int | None = None


class AiStudentInfo(BaseModel):
    studentId: int | None = None
    studentName: str | None = None
    enrollmentStatus: str | None = None


class AiActivitySummary(BaseModel):
    totalAssessments: int = 0
    submittedCount: int = 0
    missingCount: int = 0
    latestSubmittedAt: str | None = None


class AiScoreSummary(BaseModel):
    averageScoreRatio: float | None = None
    averageScore: float | None = None
    highestScore: float | None = None
    lowestScore: float | None = None
    recentTrend: list[float] = Field(default_factory=list)
    trend: Literal["IMPROVING", "STABLE", "DECLINING", "INSUFFICIENT_DATA"] | None = None


class AiAssessmentItem(BaseModel):
    assessmentId: int | None = None
    title: str | None = None
    submitted: bool = False
    score: float | None = None
    maxScore: float | None = None
    scoreRatio: float | None = None
    submittedAt: str | None = None
    feedback: str | None = None
    weakConcepts: list[str] = Field(default_factory=list)


class AiEvidenceItem(BaseModel):
    type: str | None = None
    sourceId: int | None = None
    summary: str | None = None
    rawText: str | None = None
    occurredAt: str | None = None


class AiCompetency(BaseModel):
    key: str | None = None
    label: str | None = None
    score: float | None = None
    level: Literal[
        "EXCELLENT",
        "GOOD",
        "WATCH",
        "NEEDS_IMPROVEMENT",
        "INSUFFICIENT_DATA",
    ] | None = None
    latestFeedback: str | None = None
    evidenceCount: int = 0
    evidence: list[AiEvidenceItem] = Field(default_factory=list)


class AiNarrative(BaseModel):
    summary: str | None = None
    strengths: list[str] = Field(default_factory=list)
    weaknesses: list[str] = Field(default_factory=list)


class StudentAiReportContext(BaseModel):
    course: AiCourseInfo = Field(default_factory=AiCourseInfo)
    student: AiStudentInfo = Field(default_factory=AiStudentInfo)
    activitySummary: AiActivitySummary = Field(default_factory=AiActivitySummary)
    scoreSummary: AiScoreSummary = Field(default_factory=AiScoreSummary)
    assessments: list[AiAssessmentItem] = Field(default_factory=list)
    competencies: list[AiCompetency] = Field(default_factory=list)
    evidence: list[AiEvidenceItem] = Field(default_factory=list)
    existingNarrative: AiNarrative | None = None
    reportWarnings: list[str] = Field(default_factory=list)


class StudentReportAnalyzeRequest(BaseModel):
    context: StudentAiReportContext
    model: str | None = None


class StudentReportChatMessage(BaseModel):
    role: Literal["user", "assistant"] = "user"
    content: str


class StudentReportChatRequest(BaseModel):
    context: StudentAiReportContext
    question: str
    report: dict[str, Any] | None = None
    history: list[StudentReportChatMessage] = Field(default_factory=list)
    model: str | None = None


class CompetencyAnalysis(BaseModel):
    key: str | None = None
    label: str | None = None
    score: float | None = None
    level: str | None = None
    analysis: str


class StudentReportAnalysis(BaseModel):
    studentId: int | None = None
    courseId: int | None = None
    summary: str
    strengths: list[str]
    weaknesses: list[str]
    competencyAnalysis: list[CompetencyAnalysis]
    teachingSuggestions: list[str]
    followUpQuestions: list[str]
    confidence: Literal["LOW", "MEDIUM", "HIGH"]
    evidenceUsed: list[str] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)


def _model_name(requested: str | None) -> str:
    return model_name(requested)


def _api_key() -> str:
    from app.services.gemini_service import api_key

    return api_key()


def _ndjson(payload: dict[str, Any]) -> bytes:
    return (json.dumps(payload, ensure_ascii=False) + "\n").encode("utf-8")


def _top_weak_concepts(context: StudentAiReportContext) -> list[str]:
    seen: dict[str, bool] = {}
    for item in context.assessments:
        for concept in item.weakConcepts:
            if concept and concept.strip():
                seen.setdefault(concept.strip(), True)
    return list(seen.keys())[:5]


def _fallback_analysis(
    context: StudentAiReportContext,
    reason: str | None = None,
) -> StudentReportAnalysis:
    score = context.scoreSummary.averageScore
    trend = context.scoreSummary.trend or "INSUFFICIENT_DATA"
    weak_concepts = _top_weak_concepts(context)
    strong = [
        c for c in context.competencies
        if c.level in {"EXCELLENT", "GOOD"} or (c.score is not None and c.score >= 80)
    ]
    weak = [
        c for c in context.competencies
        if c.level in {"WATCH", "NEEDS_IMPROVEMENT"} or (c.score is not None and c.score < 70)
    ]

    student_name = context.student.studentName or "해당 학생"
    course_name = context.course.courseName or "해당 강의"
    score_phrase = "점수 데이터가 아직 충분하지 않습니다" if score is None else f"평균 {score:.1f}점입니다"
    summary = f"{student_name} 학생은 {course_name}에서 {score_phrase}. 최근 추세는 {trend}로 분류됩니다."
    if weak_concepts:
        summary += f" 반복 확인이 필요한 개념은 {', '.join(weak_concepts)}입니다."

    strengths = [f"{c.label or c.key} 영역이 안정적입니다." for c in strong[:3]]
    if not strengths and context.existingNarrative:
        strengths = context.existingNarrative.strengths
    strengths = strengths or ["제출 및 평가 데이터를 바탕으로 강점을 추가 확인해야 합니다."]

    weaknesses = [f"{c.label or c.key} 영역은 보강이 필요합니다." for c in weak[:3]]
    if not weaknesses:
        weaknesses = [f"{concept} 개념을 다시 점검해야 합니다." for concept in weak_concepts[:3]]
    if not weaknesses and context.existingNarrative:
        weaknesses = context.existingNarrative.weaknesses
    weaknesses = weaknesses or ["아직 명확한 약점 근거가 부족합니다."]

    competency_analysis = [
        CompetencyAnalysis(
            key=c.key,
            label=c.label,
            score=c.score,
            level=c.level,
            analysis=(
                f"{c.label or c.key or '역량'} 점수는 "
                f"{c.score if c.score is not None else '미확인'}이며, "
                f"최근 피드백은 {c.latestFeedback or '제공되지 않았습니다'}."
            ),
        )
        for c in context.competencies[:6]
    ]
    evidence_used = [e.summary for e in context.evidence if e.summary][:8]
    warnings = list(context.reportWarnings)
    if reason:
        warnings.append(reason)

    if len(context.evidence) >= 5 and context.scoreSummary.averageScore is not None:
        confidence: Literal["LOW", "MEDIUM", "HIGH"] = "HIGH"
    elif context.evidence or context.assessments or context.competencies:
        confidence = "MEDIUM"
    else:
        confidence = "LOW"

    return StudentReportAnalysis(
        studentId=context.student.studentId,
        courseId=context.course.courseId,
        summary=summary,
        strengths=strengths[:5],
        weaknesses=weaknesses[:5],
        competencyAnalysis=competency_analysis,
        teachingSuggestions=[
            "가장 낮은 역량 또는 반복 오답 개념을 기준으로 짧은 보충 활동을 먼저 배정하세요.",
            "다음 평가 전에는 약점 개념을 포함한 OX/객관식 점검으로 이해 여부를 빠르게 확인하세요.",
            "피드백 원문이 부족한 경우 교사가 관찰 근거를 추가해 다음 리포트 품질을 높이세요.",
        ],
        followUpQuestions=[
            "최근 오답이 반복된 개념을 학생이 자신의 말로 설명할 수 있나요?",
            "다음 평가에서 같은 개념을 다른 문제 상황에 적용할 수 있나요?",
            "학생에게 가장 부담이 큰 평가 유형은 무엇인가요?",
        ],
        confidence=confidence,
        evidenceUsed=evidence_used,
        warnings=warnings,
    )


def _analysis_schema() -> dict[str, Any]:
    return {
        "type": "object",
        "properties": {
            "studentId": {"type": ["integer", "null"]},
            "courseId": {"type": ["integer", "null"]},
            "summary": {"type": "string"},
            "strengths": {"type": "array", "items": {"type": "string"}},
            "weaknesses": {"type": "array", "items": {"type": "string"}},
            "competencyAnalysis": {"type": "array", "items": {"type": "object"}},
            "teachingSuggestions": {"type": "array", "items": {"type": "string"}},
            "followUpQuestions": {"type": "array", "items": {"type": "string"}},
            "confidence": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]},
            "evidenceUsed": {"type": "array", "items": {"type": "string"}},
            "warnings": {"type": "array", "items": {"type": "string"}},
        },
        "required": [
            "summary",
            "strengths",
            "weaknesses",
            "competencyAnalysis",
            "teachingSuggestions",
            "followUpQuestions",
            "confidence",
        ],
    }


def _build_analysis_prompt(context: StudentAiReportContext) -> str:
    return f"""
너는 교사용 학생 역량 리포트 분석 에이전트다.
반드시 JSON만 출력하라. 코드블록, 설명 문장, markdown wrapper는 금지한다.
Spring Boot가 DB에서 집계한 학생 리포트 context만 근거로 분석한다.
근거가 부족한 내용은 단정하지 말고 confidence/warnings에 반영한다.

출력 JSON 필드:
studentId, courseId, summary, strengths[], weaknesses[], competencyAnalysis[],
teachingSuggestions[], followUpQuestions[], confidence, evidenceUsed[], warnings[]

Spring AI context:
{json.dumps(context.model_dump(mode='json', exclude_none=True), ensure_ascii=False)}
""".strip()


async def _call_gemini_json(prompt: str, model: str) -> dict[str, Any]:
    return await generate_json(
        prompt=prompt,
        model=model,
        response_json_schema=_analysis_schema(),
    )


async def _call_gemini_text(prompt: str, model: str) -> str:
    return await generate_text(prompt=prompt, model=model)


async def analyze_student_report(
    context: StudentAiReportContext,
    model: str | None = None,
) -> StudentReportAnalysis:
    try:
        parsed = await _call_gemini_json(_build_analysis_prompt(context), _model_name(model))
        parsed = _normalize_analysis_payload(parsed, context)
        parsed.setdefault("studentId", context.student.studentId)
        parsed.setdefault("courseId", context.course.courseId)
        parsed.setdefault("warnings", context.reportWarnings)
        parsed.setdefault("evidenceUsed", [e.summary for e in context.evidence if e.summary][:8])
        return StudentReportAnalysis.model_validate(parsed)
    except Exception as exc:  # noqa: BLE001
        return _fallback_analysis(context, reason=f"ai_fallback:{type(exc).__name__}")


def _normalize_analysis_payload(
    parsed: dict[str, Any],
    context: StudentAiReportContext,
) -> dict[str, Any]:
    if not isinstance(parsed.get("competencyAnalysis"), list):
        parsed["competencyAnalysis"] = []

    normalized: list[dict[str, Any]] = []
    context_competencies = context.competencies
    for index, raw_item in enumerate(parsed["competencyAnalysis"]):
        item = raw_item if isinstance(raw_item, dict) else {}
        source = context_competencies[index] if index < len(context_competencies) else None
        item.setdefault("key", source.key if source else None)
        item.setdefault("label", source.label if source else None)
        item.setdefault("score", source.score if source else None)
        item.setdefault("level", source.level if source else None)
        if not item.get("analysis"):
            label = item.get("label") or item.get("key") or "해당 역량"
            score = item.get("score")
            feedback = source.latestFeedback if source else None
            score_text = "점수 근거가 부족합니다" if score is None else f"{score}점입니다"
            item["analysis"] = f"{label}은 {score_text}. {feedback or '추가 근거 확보가 필요합니다.'}"
        normalized.append(item)

    if not normalized and context_competencies:
        for source in context_competencies[:6]:
            normalized.append({
                "key": source.key,
                "label": source.label,
                "score": source.score,
                "level": source.level,
                "analysis": (
                    f"{source.label or source.key or '해당 역량'}은 "
                    f"{source.score if source.score is not None else '점수 미확인'} 수준이며, "
                    f"{source.latestFeedback or '추가 근거 확보가 필요합니다.'}"
                ),
            })

    parsed["competencyAnalysis"] = normalized
    return parsed


@router.post("/student/analyze", response_model=StudentReportAnalysis)
async def analyze_student_report_endpoint(request: StudentReportAnalyzeRequest) -> StudentReportAnalysis:
    return await analyze_student_report(request.context, request.model)


@router.post("/student/analyze/stream")
async def analyze_student_report_stream(request: StudentReportAnalyzeRequest) -> StreamingResponse:
    async def events() -> AsyncIterator[bytes]:
        try:
            yield _ndjson({"type": "agent_delta", "channel": "thought", "text": "학생 리포트 context를 검토하고 있습니다."})
            analysis = await analyze_student_report(request.context, request.model)
            yield _ndjson({"type": "agent_delta", "channel": "main", "text": analysis.summary})
            yield _ndjson({"type": "done", "data": analysis.model_dump(mode="json")})
        except Exception as exc:  # noqa: BLE001
            yield _ndjson({
                "type": "error",
                "code": "REPORT_ANALYSIS_FAILED",
                "message": "학생 리포트 분석 중 오류가 발생했습니다.",
                "details": {"errorType": type(exc).__name__},
            })

    return StreamingResponse(events(), media_type="application/x-ndjson")


def _build_chat_answer(request: StudentReportChatRequest) -> str:
    analysis = request.report or _fallback_analysis(request.context).model_dump(mode="json")
    weak_concepts = _top_weak_concepts(request.context)
    evidence = [e.summary for e in request.context.evidence if e.summary][:5]
    return "\n".join([
        f"질문: {request.question}",
        "",
        f"{request.context.student.studentName or '해당 학생'} 학생의 현재 리포트 기준으로 답변합니다.",
        f"- 요약: {analysis.get('summary', '저장된 요약이 없습니다.')}",
        f"- 주요 약점: {', '.join(analysis.get('weaknesses', [])[:3]) or ', '.join(weak_concepts) or '추가 근거 필요'}",
        f"- 참고 근거: {', '.join(evidence) if evidence else '제공된 evidence가 부족합니다.'}",
        "",
        "지도 제안: 가장 낮은 역량이나 반복 오답 개념을 먼저 짧게 재점검한 뒤, 유사 문항으로 전이 여부를 확인하세요.",
    ])


def _build_chat_prompt(request: StudentReportChatRequest) -> str:
    history = [
        {"role": item.role, "content": item.content}
        for item in request.history[-6:]
    ]
    return f"""
너는 교사용 학생 리포트 질의응답 에이전트다.
선택된 학생 한 명의 리포트 context와 저장 리포트만 근거로 답한다.
다른 학생, 다른 강의실, DB에 없는 사실은 추측하지 않는다.
답변은 한국어 Markdown으로 작성하되, 5문장 안에서 교사가 바로 쓸 수 있게 구체적으로 답한다.

학생 리포트 context:
{json.dumps(request.context.model_dump(mode='json', exclude_none=True), ensure_ascii=False)}

저장된 AI 리포트:
{json.dumps(request.report or {}, ensure_ascii=False)}

최근 대화:
{json.dumps(history, ensure_ascii=False)}

교사 질문:
{request.question}
""".strip()


async def answer_student_report_chat(request: StudentReportChatRequest) -> str:
    try:
        return await _call_gemini_text(_build_chat_prompt(request), _model_name(request.model))
    except Exception:  # noqa: BLE001
        return _build_chat_answer(request)


@router.post("/student/chat/stream")
async def student_report_chat_stream(request: StudentReportChatRequest) -> StreamingResponse:
    async def events() -> AsyncIterator[bytes]:
        try:
            yield _ndjson({"type": "agent_delta", "channel": "thought", "text": "선택된 학생 리포트와 질문을 연결하고 있습니다."})
            answer = await answer_student_report_chat(request)
            yield _ndjson({"type": "agent_delta", "channel": "main", "text": answer})
            yield _ndjson({"type": "done", "data": {"answer": answer}})
        except Exception as exc:  # noqa: BLE001
            yield _ndjson({
                "type": "error",
                "code": "REPORT_CHAT_FAILED",
                "message": "학생 리포트 채팅 응답 생성 중 오류가 발생했습니다.",
                "details": {"errorType": type(exc).__name__},
            })

    return StreamingResponse(events(), media_type="application/x-ndjson")
