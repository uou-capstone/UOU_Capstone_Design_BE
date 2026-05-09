from __future__ import annotations

import asyncio
import hashlib
import json
import os
import time
from pathlib import Path
from typing import Any, AsyncIterator, Literal

from fastapi import APIRouter, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field
from pypdf import PdfReader

from app.routers.exam import (
    ExamStudioChatRequest,
    _call_gemini_json,
    _model_name,
    _ndjson,
    run_exam_studio_chat,
)
from app.routers.report import (
    StudentAiReportContext,
    StudentReportChatMessage,
    StudentReportChatRequest,
    answer_student_report_chat_result,
)
from app.core.path_validator import validate_pdf_path

router = APIRouter(prefix="/bridge", tags=["bridge-compatible-agents"])

_PDF_CONTEXT_TTL_SECONDS = int(os.getenv("EXAM_STUDIO_CONTEXT_TTL_SECONDS", "21600"))
_PDF_CONTEXT_MAX_BYTES = int(os.getenv("EXAM_STUDIO_PDF_CONTEXT_MAX_BYTES", str(50 * 1024 * 1024)))
_PDF_TEXT_CONTEXT_MAX_CHARS = int(os.getenv("EXAM_STUDIO_PDF_TEXT_MAX_CHARS", "500000"))
_PDF_CONTEXTS: dict[str, dict[str, Any]] = {}


class DiscussionContextItem(BaseModel):
    title: str | None = None
    category: str | None = None
    contentPreview: str | None = None
    createdAt: str | None = None


class DiscussionAssistantRequest(BaseModel):
    courseId: int | None = None
    courseName: str | None = None
    topic: str
    category: str | None = None
    previousDraft: str | None = None
    recentDiscussions: list[DiscussionContextItem] = Field(default_factory=list)
    model: str | None = None


class ExamStudioPdfContextRequest(BaseModel):
    courseId: int | None = None
    lectureId: int | None = None
    materialId: int | None = None
    pdfPath: str | None = None
    pdfText: str | None = None
    displayName: str | None = None


class ExamStudioBridgeChatRequest(BaseModel):
    contextId: str | None = None
    messages: list[StudentReportChatMessage] = Field(default_factory=list)
    message: str | None = None
    currentDraft: dict[str, Any] = Field(default_factory=dict)
    currentKstIso: str | None = None
    timeZone: str | None = None
    sourceText: str | None = None
    model: str | None = None
    responseJsonSchema: dict[str, Any] | None = None


class ReportStudentChatBridgeRequest(BaseModel):
    context: StudentAiReportContext | None = None
    report: dict[str, Any] | None = None
    messages: list[StudentReportChatMessage] = Field(default_factory=list)
    question: str | None = None
    model: str | None = None


class CriterionItem(BaseModel):
    id: int | str | None = None
    label: str
    description: str | None = None
    weight: int | None = None


class CriteriaAssistantRequest(BaseModel):
    courseId: int | None = None
    courseName: str | None = None
    existingCriteria: list[CriterionItem] = Field(default_factory=list)
    desiredCount: int = Field(default=3, ge=1, le=10)
    language: str = "ko"
    model: str | None = None


class ClassroomReportAnalyzeRequest(BaseModel):
    courseId: int | None = None
    courseName: str | None = None
    studentReports: list[dict[str, Any]] = Field(default_factory=list)
    criteria: list[CriterionItem] = Field(default_factory=list)
    model: str | None = None


def _stream_response(generator: AsyncIterator[bytes]) -> StreamingResponse:
    return StreamingResponse(generator, media_type="application/x-ndjson")


def _last_user_message(messages: list[StudentReportChatMessage]) -> str:
    for message in reversed(messages):
        if message.role == "user" and message.content.strip():
            return message.content.strip()
    return ""


def _context_text(context_id: str | None) -> str:
    if not context_id:
        return ""
    entry = _PDF_CONTEXTS.get(context_id)
    if not entry:
        return ""
    if time.time() > float(entry.get("expiresAt", 0)):
        _PDF_CONTEXTS.pop(context_id, None)
        return ""
    return str(entry.get("text") or "")


def _fallback_discussion(req: DiscussionAssistantRequest) -> dict[str, Any]:
    title = req.topic.strip()[:80] or "토론 주제 제안"
    category = req.category or "FREE"
    context_line = f"이 글은 `{category}` 카테고리의 토론 글 초안입니다."
    draft = req.previousDraft.strip() if req.previousDraft else ""
    body = "\n\n".join([
        f"## {title}",
        context_line,
        draft or "이 단원에서 이해한 내용과 아직 헷갈리는 지점을 함께 정리해 보세요.",
        "구체적인 예시나 질문을 한 가지 이상 포함하면 더 좋은 토론 글이 됩니다.",
    ])
    return {
        "title": title,
        "contentMarkdown": body,
        "source": "FALLBACK",
        "fallbackUsed": True,
        "reason": "ai_fallback",
        "confidence": "LOW",
        "warnings": ["ai_fallback"],
    }


def _discussion_schema() -> dict[str, Any]:
    return {
        "type": "object",
        "properties": {
            "title": {"type": "string"},
            "contentMarkdown": {"type": "string"},
            "source": {"type": "string"},
            "warnings": {"type": "array", "items": {"type": "string"}},
        },
        "required": ["title", "contentMarkdown"],
    }


async def _discussion_result(req: DiscussionAssistantRequest) -> dict[str, Any]:
    prompt = f"""
너는 학생의 토론 글 작성을 돕는 AI assistant다.
반드시 JSON만 출력한다.
출력 필드: title, contentMarkdown, source, warnings[]

강의명: {req.courseName or '(강의명 없음)'}
카테고리: {req.category or 'FREE'}
학생 키워드/주제: {req.topic}
부분 작성 초안:
{req.previousDraft or '(없음)'}

최근 토론 글:
{json.dumps([item.model_dump(mode='json', exclude_none=True) for item in req.recentDiscussions[:10]], ensure_ascii=False)}

작성 규칙:
- 학생이 그대로 게시할 수 있는 제목과 본문 초안을 제안한다.
- 질문형 카테고리면 명확한 질문 1개를 포함한다.
- 자료 공유형이면 출처/근거를 적을 공간을 남긴다.
- 한국어 Markdown으로 작성한다.
""".strip()
    try:
        parsed = await _call_gemini_json(prompt=prompt, model=_model_name(req.model))
        if not isinstance(parsed.get("title"), str) or not isinstance(parsed.get("contentMarkdown"), str):
            raise ValueError("invalid_discussion_payload")
        if not isinstance(parsed.get("source"), str) or not parsed["source"].strip():
            parsed["source"] = "AI"
        parsed["fallbackUsed"] = False
        parsed["reason"] = None
        parsed["confidence"] = "MEDIUM"
        parsed.setdefault("warnings", [])
        return parsed
    except Exception:  # noqa: BLE001
        return _fallback_discussion(req)


def _validate_pdf_context_path(pdf_path: str) -> Path:
    safe_path = Path(validate_pdf_path(pdf_path))
    if safe_path.suffix.lower() != ".pdf":
        raise HTTPException(status_code=400, detail="pdfPath must point to a PDF file.")

    stat = safe_path.stat()
    if stat.st_size > _PDF_CONTEXT_MAX_BYTES:
        raise HTTPException(status_code=413, detail="PDF file is too large for exam studio context.")

    with safe_path.open("rb") as handle:
        if handle.read(5) != b"%PDF-":
            raise HTTPException(status_code=400, detail="Invalid PDF file header.")

    return safe_path


def _extract_pdf_context(req: ExamStudioPdfContextRequest) -> dict[str, Any]:
    if req.pdfText:
        if len(req.pdfText) > _PDF_TEXT_CONTEXT_MAX_CHARS:
            raise HTTPException(status_code=413, detail="pdfText is too large for exam studio context.")
        pages = [{"page": 1, "text": req.pdfText}]
        raw_key = f"text:{req.courseId}:{req.lectureId}:{req.materialId}:{req.pdfText[:200]}"
    elif req.pdfPath:
        path = _validate_pdf_context_path(req.pdfPath)
        reader = PdfReader(str(path))
        pages = [
            {"page": index + 1, "text": (page.extract_text() or "").strip()}
            for index, page in enumerate(reader.pages)
        ]
        stat = path.stat()
        raw_key = f"path:{path.resolve()}:{stat.st_mtime_ns}:{stat.st_size}"
    else:
        raise ValueError("pdfPath or pdfText is required")

    full_text = "\n\n".join(
        f"[page {page['page']}]\n{page['text']}"
        for page in pages
        if page["text"]
    )
    context_id = hashlib.sha256(raw_key.encode("utf-8")).hexdigest()[:24]
    now = time.time()
    _PDF_CONTEXTS[context_id] = {
        "contextId": context_id,
        "courseId": req.courseId,
        "lectureId": req.lectureId,
        "materialId": req.materialId,
        "displayName": req.displayName,
        "pageCount": len(pages),
        "charCount": len(full_text),
        "text": full_text[:120000],
        "createdAt": now,
        "expiresAt": now + _PDF_CONTEXT_TTL_SECONDS,
    }
    return {
        "contextId": context_id,
        "pageCount": len(pages),
        "charCount": len(full_text),
        "expiresInSeconds": _PDF_CONTEXT_TTL_SECONDS,
        "cacheScope": "PROCESS_MEMORY",
        "bestEffort": True,
    }


def _bridge_exam_request(req: ExamStudioBridgeChatRequest) -> ExamStudioChatRequest:
    message = req.message or _last_user_message(req.messages)
    source_text = "\n\n".join(text for text in [_context_text(req.contextId), req.sourceText or ""] if text)
    return ExamStudioChatRequest(
        model=req.model,
        message=message or "시험 문항 초안을 제안해줘",
        currentDraft=req.currentDraft,
        currentKstIso=req.currentKstIso,
        timeZone=req.timeZone,
        sourceText=source_text or None,
        responseJsonSchema=req.responseJsonSchema,
    )


def _fallback_criteria(req: CriteriaAssistantRequest) -> list[dict[str, Any]]:
    existing = {item.label.strip() for item in req.existingCriteria}
    base = [
        {
            "label": "개념 이해도",
            "description": "강의 핵심 개념을 정확히 설명하고 구분할 수 있는가",
            "weight": 35,
            "source": "FALLBACK",
            "fallbackUsed": True,
            "reason": "ai_fallback",
            "confidence": "LOW",
        },
        {
            "label": "문제 해결 적용력",
            "description": "개념을 새로운 문제 상황에 적용해 풀이 전략을 세울 수 있는가",
            "weight": 35,
            "source": "FALLBACK",
            "fallbackUsed": True,
            "reason": "ai_fallback",
            "confidence": "LOW",
        },
        {
            "label": "학습 참여와 성찰",
            "description": "질문, 제출, 피드백 반영을 통해 학습 과정을 개선하는가",
            "weight": 30,
            "source": "FALLBACK",
            "fallbackUsed": True,
            "reason": "ai_fallback",
            "confidence": "LOW",
        },
    ]
    return [item for item in base if item["label"] not in existing][: req.desiredCount]


def _criteria_schema() -> dict[str, Any]:
    return {
        "type": "object",
        "properties": {
            "suggestions": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "label": {"type": "string"},
                        "description": {"type": "string"},
                        "weight": {"type": "integer"},
                    },
                    "required": ["label", "description", "weight"],
                },
            },
        },
        "required": ["suggestions"],
    }


async def _criteria_suggestions(req: CriteriaAssistantRequest) -> list[dict[str, Any]]:
    prompt = f"""
너는 교사용 강의실 리포트 평가 기준 추천 assistant다.
반드시 JSON만 출력한다.
출력 필드: suggestions[].
각 suggestion은 label, description, weight를 가진다.
weight는 0~100 정수이며 전체 추천 기준의 합은 100에 가깝게 맞춘다.

강의명: {req.courseName or '(강의명 없음)'}
추천 개수: {req.desiredCount}
기존 기준:
{json.dumps([item.model_dump(mode='json', exclude_none=True) for item in req.existingCriteria], ensure_ascii=False)}
""".strip()
    try:
        parsed = await _call_gemini_json(prompt=prompt, model=_model_name(req.model))
        raw = parsed.get("suggestions")
        if not isinstance(raw, list):
            raise ValueError("suggestions_not_list")
        out: list[dict[str, Any]] = []
        for item in raw[: req.desiredCount]:
            if not isinstance(item, dict) or not item.get("label"):
                continue
            out.append({
                "label": str(item["label"]).strip(),
                "description": str(item.get("description") or "").strip(),
                "weight": int(item.get("weight") or 0),
                "source": "AI",
                "fallbackUsed": False,
                "reason": None,
                "confidence": "MEDIUM",
            })
        return out or _fallback_criteria(req)
    except Exception:  # noqa: BLE001
        return _fallback_criteria(req)


def _fallback_classroom_report(req: ClassroomReportAnalyzeRequest) -> dict[str, Any]:
    count = len(req.studentReports)
    course_name = req.courseName or "해당 강의"
    summary = f"{course_name}의 학생 {count}명 리포트를 기준으로 종합 분석했습니다."
    return {
        "courseId": req.courseId,
        "summaryMarkdown": f"## 강의실 종합 요약\n\n{summary}\n\n## 코칭 우선순위\n\n- 점수가 낮거나 evidence가 부족한 학생을 먼저 확인하세요.\n- 공통 약점 개념을 기준으로 보충 활동을 설계하세요.",
        "highlights": ["학생별 리포트 기반 종합 요약"],
        "risks": ["데이터가 부족한 학생은 개별 확인이 필요합니다."],
        "coachingPriorities": ["공통 약점 개념 보강", "저득점 학생 우선 피드백"],
        "source": "FALLBACK",
        "fallbackUsed": True,
        "reason": "ai_fallback",
        "confidence": "LOW",
        "warnings": ["ai_fallback"],
    }


def _classroom_schema() -> dict[str, Any]:
    return {
        "type": "object",
        "properties": {
            "courseId": {"type": ["integer", "null"]},
            "summaryMarkdown": {"type": "string"},
            "highlights": {"type": "array", "items": {"type": "string"}},
            "risks": {"type": "array", "items": {"type": "string"}},
            "coachingPriorities": {"type": "array", "items": {"type": "string"}},
            "source": {"type": "string"},
            "warnings": {"type": "array", "items": {"type": "string"}},
        },
        "required": ["summaryMarkdown", "highlights", "risks", "coachingPriorities"],
    }


def _context_from_report(report: dict[str, Any] | None) -> StudentAiReportContext:
    report = report or {}
    student_raw = report.get("student") if isinstance(report.get("student"), dict) else {}
    course_raw = report.get("course") if isinstance(report.get("course"), dict) else {}
    score_raw = report.get("scoreSummary") if isinstance(report.get("scoreSummary"), dict) else {}
    activity_raw = report.get("activitySummary") if isinstance(report.get("activitySummary"), dict) else {}
    return StudentAiReportContext.model_validate({
        "course": {
            "courseId": course_raw.get("courseId") or report.get("courseId"),
            "courseName": course_raw.get("courseName") or course_raw.get("title") or report.get("courseName"),
            "teacherId": course_raw.get("teacherId"),
        },
        "student": {
            "studentId": student_raw.get("studentId") or report.get("studentId"),
            "studentName": student_raw.get("studentName") or report.get("studentName"),
            "enrollmentStatus": student_raw.get("enrollmentStatus"),
        },
        "activitySummary": activity_raw,
        "scoreSummary": score_raw,
        "assessments": report.get("assessments") or [],
        "competencies": report.get("competencies") or [],
        "evidence": report.get("evidence") or [],
        "existingNarrative": report.get("existingNarrative") or report.get("narrativeReport"),
        "reportWarnings": report.get("reportWarnings") or [],
    })


async def _classroom_report(req: ClassroomReportAnalyzeRequest) -> dict[str, Any]:
    prompt = f"""
너는 교사용 강의실 종합 리포트 분석 에이전트다.
반드시 JSON만 출력한다.
출력 필드:
- courseId
- summaryMarkdown
- highlights[]
- risks[]
- coachingPriorities[]
- source
- warnings[]

강의 정보:
{json.dumps({"courseId": req.courseId, "courseName": req.courseName}, ensure_ascii=False)}

평가 기준:
{json.dumps([item.model_dump(mode='json', exclude_none=True) for item in req.criteria], ensure_ascii=False)}

학생별 리포트:
{json.dumps(req.studentReports[:120], ensure_ascii=False)}
""".strip()
    try:
        parsed = await _call_gemini_json(prompt=prompt, model=_model_name(req.model))
        if not isinstance(parsed.get("summaryMarkdown"), str):
            raise ValueError("invalid_classroom_report")
        parsed.setdefault("courseId", req.courseId)
        if not isinstance(parsed.get("source"), str) or not parsed["source"].strip():
            parsed["source"] = "AI"
        parsed["fallbackUsed"] = False
        parsed["reason"] = None
        parsed["confidence"] = "MEDIUM"
        parsed.setdefault("warnings", [])
        return parsed
    except Exception:  # noqa: BLE001
        return _fallback_classroom_report(req)


@router.post("/discussion_assistant_stream")
async def discussion_assistant_stream(req: DiscussionAssistantRequest) -> StreamingResponse:
    async def events() -> AsyncIterator[bytes]:
        try:
            yield _ndjson({"type": "thought_delta", "text": "토론 글 작성을 위한 강의 맥락을 확인하고 있습니다."})
            result = await _discussion_result(req)
            yield _ndjson({"type": "answer_delta", "text": result.get("contentMarkdown", "")})
            yield _ndjson({"type": "done", "data": result})
        except Exception as exc:  # noqa: BLE001
            yield _ndjson({
                "type": "error",
                "code": "DISCUSSION_ASSISTANT_FAILED",
                "message": "토론 글 작성 보조 응답 생성 중 오류가 발생했습니다.",
                "details": {"errorType": type(exc).__name__},
            })

    return _stream_response(events())


@router.post("/exam_studio/pdf_context")
async def exam_studio_pdf_context(req: ExamStudioPdfContextRequest) -> dict[str, Any]:
    return await asyncio.to_thread(_extract_pdf_context, req)


@router.post("/exam_studio/chat_stream")
async def exam_studio_chat_stream(req: ExamStudioBridgeChatRequest) -> StreamingResponse:
    async def events() -> AsyncIterator[bytes]:
        try:
            yield _ndjson({"type": "thought_delta", "text": "시험 스튜디오 context와 교사 요청을 분석하고 있습니다."})
            result = await run_exam_studio_chat(_bridge_exam_request(req))
            yield _ndjson({"type": "answer_delta", "text": result.answerMarkdown})
            yield _ndjson({"type": "done", "data": result.model_dump(mode="json")})
        except Exception as exc:  # noqa: BLE001
            yield _ndjson({
                "type": "error",
                "code": "EXAM_STUDIO_CHAT_FAILED",
                "message": "시험 스튜디오 응답 생성 중 오류가 발생했습니다.",
                "details": {"errorType": type(exc).__name__},
            })

    return _stream_response(events())


@router.post("/report/student_chat_stream")
async def report_student_chat_stream(req: ReportStudentChatBridgeRequest) -> StreamingResponse:
    async def events() -> AsyncIterator[bytes]:
        try:
            context = req.context or _context_from_report(req.report)
            question = req.question or _last_user_message(req.messages)
            yield _ndjson({"type": "thought_delta", "text": "학생 리포트와 질문을 연결하고 있습니다."})
            result = await answer_student_report_chat_result(StudentReportChatRequest(
                context=context,
                question=question,
                report=req.report,
                history=req.messages,
                model=req.model,
            ))
            yield _ndjson({"type": "answer_delta", "text": result["answer"]})
            yield _ndjson({"type": "done", "data": result})
        except Exception as exc:  # noqa: BLE001
            yield _ndjson({
                "type": "error",
                "code": "REPORT_STUDENT_CHAT_FAILED",
                "message": "학생 리포트 채팅 응답 생성 중 오류가 발생했습니다.",
                "details": {"errorType": type(exc).__name__},
            })

    return _stream_response(events())


@router.post("/report/criteria_assistant_stream")
async def report_criteria_assistant_stream(req: CriteriaAssistantRequest) -> StreamingResponse:
    async def events() -> AsyncIterator[bytes]:
        try:
            yield _ndjson({"type": "thought_delta", "text": "강의실 평가 기준 추천을 생성하고 있습니다."})
            suggestions = await _criteria_suggestions(req)
            for suggestion in suggestions:
                yield _ndjson({"type": "criterion_suggestion", "data": suggestion})
            yield _ndjson({
                "type": "done",
                "data": {
                    "suggestions": suggestions,
                    "fallbackUsed": any(bool(item.get("fallbackUsed")) for item in suggestions),
                },
            })
        except Exception as exc:  # noqa: BLE001
            yield _ndjson({
                "type": "error",
                "code": "CRITERIA_ASSISTANT_FAILED",
                "message": "리포트 기준 추천 생성 중 오류가 발생했습니다.",
                "details": {"errorType": type(exc).__name__},
            })

    return _stream_response(events())


@router.post("/report/classroom_analyze")
async def report_classroom_analyze(req: ClassroomReportAnalyzeRequest) -> dict[str, Any]:
    return await _classroom_report(req)


@router.post("/report/classroom_analyze_stream")
async def report_classroom_analyze_stream(req: ClassroomReportAnalyzeRequest) -> StreamingResponse:
    async def events() -> AsyncIterator[bytes]:
        try:
            yield _ndjson({"type": "thought_delta", "text": "학생별 리포트를 모아 강의실 종합 인사이트를 생성하고 있습니다."})
            result = await _classroom_report(req)
            yield _ndjson({"type": "answer_delta", "text": result.get("summaryMarkdown", "")})
            yield _ndjson({"type": "done", "data": result})
        except Exception as exc:  # noqa: BLE001
            yield _ndjson({
                "type": "error",
                "code": "CLASSROOM_REPORT_FAILED",
                "message": "강의실 종합 리포트 생성 중 오류가 발생했습니다.",
                "details": {"errorType": type(exc).__name__},
            })

    return _stream_response(events())
