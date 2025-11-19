from fastapi import APIRouter, HTTPException, BackgroundTasks, Request
from pydantic import BaseModel
import traceback
import logging
import asyncio
import httpx
import os
from pathlib import Path
from dotenv import load_dotenv
from datetime import datetime
from typing import Dict, Any
from ai_agent.Lecture_Agent.integration import (
    main as run_full_pipeline,
    prepare_lecture_content,
    generate_supplementary_explanation,
    initialize_lecture,
    generate_single_chapter,
    get_next_segment,
)

# .env 파일 로드
load_dotenv()

logger = logging.getLogger(__name__)

# 파이프라인 세션 관리 (간단한 인메모리 저장소)
pipeline_sessions: Dict[int, Dict[str, Any]] = {}
pipeline_lock = asyncio.Lock()

class DelegatorDispatchRequest(BaseModel):
    stage: str
    payload: dict

router = APIRouter(prefix="/api/delegator", tags=["delegator"])


def convert_to_ai_response_dto(chapters_info, lecture_results):
    """
    integration.py의 결과를 Spring Boot의 AiResponseDto 형식으로 변환
    질문 토큰([질문]...[/질문])을 포함한 세그먼트 구조로 변환
    
    Args:
        chapters_info: List[Tuple[str, str]] - (chapter_title, pdf_path)
        lecture_results: List[Dict[str, str]] - [{chapter_title: explanation}, ...]
    
    Returns:
        List[Dict] - AiResponseDto 형식의 리스트 (질문 포함 세그먼트)
    """
    from ai_agent.Lecture_Agent.integration import build_segments_from_explanation
    
    ai_responses = []
    
    for chapter_idx, ((chapter_title, pdf_path), lecture_dict) in enumerate(zip(chapters_info, lecture_results)):
        explanation = lecture_dict.get(chapter_title, "")
        
        # 질문을 포함한 세그먼트로 분리
        segments, question_meta = build_segments_from_explanation(
            explanation,
            prefix=f"c{chapter_idx}-"
        )
        
        # 각 세그먼트를 AiResponseDto로 변환
        for segment in segments:
            if segment.get("type") == "script":
                # 스크립트 세그먼트
                ai_responses.append({
                    "contentType": "SCRIPT",
                    "contentData": segment.get("content", ""),
                    "materialReferences": pdf_path
                })
            elif segment.get("type") == "question":
                # 질문 세그먼트 - aiQuestionId 필드로 프론트엔드에서 질문 감지 가능
                question_text = segment.get("question", "")
                ai_question_id = segment.get("questionId", "")  # integration.py에서는 questionId로 생성됨
                ai_responses.append({
                    "contentType": "SCRIPT",  # Spring Boot ContentType enum에 QUESTION이 없으므로 SCRIPT 사용
                    "contentData": question_text,  # 질문 내용만 포함 (토큰 없이)
                    "materialReferences": pdf_path,
                    "aiQuestionId": ai_question_id  # ✅ 이 필드가 있으면 프론트엔드에서 질문으로 처리
                })
    
    return ai_responses


def build_question_index(chapters: Any) -> Dict[str, Dict[str, Any]]:
    """chapters 구조를 순회해 질문 인덱스를 생성"""
    question_index: Dict[str, Dict[str, Any]] = {}

    for chapter_idx, chapter in enumerate(chapters):
        chapter_title = chapter.get("chapterTitle")
        pdf_path = chapter.get("pdfPath")
        questions = (chapter.get("questions") or {}).items()

        for question_id, meta in questions:
            question_index[question_id] = {
                "chapterIndex": chapter_idx,
                "chapterTitle": chapter_title,
                "question": meta.get("question"),
                "questionIndex": meta.get("questionIndex"),
                "pdfPath": pdf_path,
                "answered": False,
                "answer": None,
                "supplementary": None,
                "answeredAt": None,
            }

    return question_index


async def handle_generate_script_stage(lecture_id: int, pdf_path: str):
    """강의 스크립트와 질문을 준비하고 세션을 저장"""
    structured_content = await asyncio.to_thread(prepare_lecture_content, pdf_path)

    chapters = structured_content.get("chapters", [])
    question_index = build_question_index(chapters)

    async with pipeline_lock:
        existing_session = pipeline_sessions.get(lecture_id, {})
        created_at = existing_session.get("createdAt", datetime.utcnow().isoformat())
        session_payload = {
            "lectureId": lecture_id,
            "pdfPath": pdf_path,
            "status": "ready",
            "chapters": chapters,
            "questions": question_index,
            "createdAt": created_at,
            "updatedAt": datetime.utcnow().isoformat(),
            "error": None,
        }
        pipeline_sessions[lecture_id] = session_payload

    return {
        "status": "ready",
        "lectureId": lecture_id,
        "chapters": chapters,
        "questionCount": len(question_index),
    }


async def start_generate_script_background(lecture_id: int, pdf_path: str):
    """generate_script 요청을 백그라운드에서 처리 (내부 async 함수)"""
    now_iso = datetime.utcnow().isoformat()
    async with pipeline_lock:
        existing_session = pipeline_sessions.get(lecture_id, {})
        pipeline_sessions[lecture_id] = {
            "lectureId": lecture_id,
            "pdfPath": pdf_path,
            "status": "preparing",
            "chapters": existing_session.get("chapters", []),
            "questions": existing_session.get("questions", {}),
            "createdAt": existing_session.get("createdAt", now_iso),
            "updatedAt": now_iso,
            "error": None,
        }

    try:
        await handle_generate_script_stage(lecture_id, pdf_path)
    except Exception as exc:
        async with pipeline_lock:
            session = pipeline_sessions.get(lecture_id, {})
            session.update({
                "status": "error",
                "error": str(exc),
                "updatedAt": datetime.utcnow().isoformat(),
            })
            pipeline_sessions[lecture_id] = session
        logger.error(f"generate_script 실패: lecture_id={lecture_id}, error={exc}", exc_info=True)


def start_generate_script_background_sync(lecture_id: int, pdf_path: str):
    """
    BackgroundTasks용 동기 래퍼 함수
    FastAPI 공식 문서: BackgroundTasks는 동기 함수를 받아야 함
    """
    asyncio.run(start_generate_script_background(lecture_id, pdf_path))


async def handle_answer_question_stage(payload: Dict[str, Any]):
    """질문 답변을 받아 보충 설명을 생성 (스트리밍/배치 모드 공통)"""
    if not isinstance(payload, dict):
        raise HTTPException(status_code=400, detail="payload must be a dictionary")

    lecture_id = payload.get("lectureId") or payload.get("lecture_id")
    ai_question_id = payload.get("aiQuestionId")
    user_answer = payload.get("answer")

    if lecture_id is None:
        raise HTTPException(status_code=400, detail="payload.lectureId is required")
    if ai_question_id is None:
        raise HTTPException(status_code=400, detail="payload.aiQuestionId is required")
    if user_answer is None:
        raise HTTPException(status_code=400, detail="payload.answer is required")

    try:
        lecture_id_int = int(lecture_id)
    except (ValueError, TypeError):
        raise HTTPException(status_code=400, detail="lectureId must be convertible to int")

    async with pipeline_lock:
        session = pipeline_sessions.get(lecture_id_int)
        if session is None:
            raise HTTPException(status_code=404, detail=f"lectureId {lecture_id} session not found")

        session_mode = session.get("mode", "batch")
        
        # 스트리밍 모드인 경우
        if session_mode == "streaming":
            # allQuestions에서 질문 찾기
            question_entry = session.get("allQuestions", {}).get(ai_question_id)
            if question_entry is None:
                raise HTTPException(
                    status_code=404,
                    detail=f"aiQuestionId {ai_question_id} not found for lectureId {lecture_id}"
                )
            
            # 대기 중인 질문인지 확인
            if not session.get("waitingForAnswer") or session.get("currentQuestionId") != ai_question_id:
                raise HTTPException(
                    status_code=400,
                    detail=f"Not waiting for answer to question {ai_question_id}"
                )
        else:
            # 배치 모드 (기존 방식)
            question_entry = session.get("questions", {}).get(ai_question_id)
            if question_entry is None:
                raise HTTPException(
                    status_code=404,
                    detail=f"aiQuestionId {ai_question_id} not found for lectureId {lecture_id}"
                )
        
        # 중복 답변 방지
        if question_entry.get("answered"):
            raise HTTPException(
                status_code=400,
                detail=f"이미 답변한 질문입니다. (질문 ID: {ai_question_id})"
            )

    question_text = question_entry.get("question")
    pdf_path = question_entry.get("pdfPath")

    if not question_text:
        raise HTTPException(status_code=400, detail="Stored question text is empty.")
    if not pdf_path:
        raise HTTPException(status_code=400, detail="PDF path is missing in session.")

    # 보충 설명 생성
    qa_result = await asyncio.to_thread(
        generate_supplementary_explanation,
        question_text,
        user_answer,
        pdf_path
    )
    supplementary_explanation = qa_result.get("supplementary_explanation", "")
    validation_result = qa_result.get("validation_result", "")
    concept_queue = qa_result.get("concept_queue") or []
    bad_mode_history = qa_result.get("bad_mode_history") or []
    needs_follow_up = bool(qa_result.get("needs_follow_up"))
    qa_state = qa_result.get("state", "GOOD")

    # 세션 업데이트
    async with pipeline_lock:
        question_entry.update({
            "answered": True,
            "answer": user_answer,
            "supplementary": supplementary_explanation,
            "answeredAt": datetime.utcnow().isoformat(),
            "validationResult": validation_result,
            "needsFollowUp": needs_follow_up,
            "conceptQueue": concept_queue,
            "badModeHistory": bad_mode_history,
            "qaState": qa_state,
        })
        
        if session_mode == "streaming":
            # 스트리밍 모드: 대기 상태 해제
            session["allQuestions"][ai_question_id] = question_entry
            session["waitingForAnswer"] = False
            session["currentQuestionId"] = None
            session["lastAnswerMeta"] = {
                "aiQuestionId": ai_question_id,
                "validationResult": validation_result,
                "needsFollowUp": needs_follow_up,
                "conceptQueue": concept_queue,
                "badModeHistory": bad_mode_history,
                "qaState": qa_state,
                "updatedAt": datetime.utcnow().isoformat()
            }
        else:
            # 배치 모드: 기존 방식
            session["questions"][ai_question_id] = question_entry
        
        session["updatedAt"] = datetime.utcnow().isoformat()
        pipeline_sessions[lecture_id_int] = session

    return {
        "status": "success",
        "lectureId": lecture_id_int,
        "aiQuestionId": ai_question_id,
        "question": question_text,
        "chapterTitle": question_entry.get("chapterTitle"),
        "supplementary": supplementary_explanation,
        "validationResult": validation_result,
        "needsFollowUp": needs_follow_up,
        "conceptQueue": concept_queue,
        "badModeHistory": bad_mode_history,
        "qaState": qa_state,
        "canContinue": True  # 스트리밍 모드에서 다음 콘텐츠 요청 가능
    }


async def handle_initialize_stage(payload: Dict[str, Any]):
    """
    PDF 분석만 수행하여 챕터 리스트 반환 (강의는 생성하지 않음)
    스트리밍 모드의 첫 단계
    """
    if not isinstance(payload, dict):
        raise HTTPException(status_code=400, detail="payload must be a dictionary")
    
    lecture_id = payload.get("lectureId") or payload.get("lecture_id")
    pdf_path = payload.get("pdf_path")
    
    if lecture_id is None:
        raise HTTPException(status_code=400, detail="payload.lectureId is required")
    if not pdf_path:
        raise HTTPException(status_code=400, detail="payload.pdf_path is required")
    
    try:
        lecture_id_int = int(lecture_id)
    except (ValueError, TypeError):
        raise HTTPException(status_code=400, detail="lectureId must be convertible to int")
    
    # PDF 분석 실행 (챕터 정보와 원본 PDF 경로 반환)
    chapters_info, original_pdf_path = await asyncio.to_thread(initialize_lecture, pdf_path)
    
    # 세션 초기화
    now_iso = datetime.utcnow().isoformat()
    async with pipeline_lock:
        pipeline_sessions[lecture_id_int] = {
            "lectureId": lecture_id_int,
            "pdfPath": original_pdf_path,
            "mode": "streaming",
            "status": "initialized",
            "chaptersInfo": chapters_info,  # [{"chapter_title": str, "start_page": int, "end_page": int}, ...]
            "totalChapters": len(chapters_info),
            "currentChapterIndex": 0,
            "currentSegmentIndex": 0,
            "waitingForAnswer": False,
            "currentQuestionId": None,
            "generatedChapters": {},  # 캐싱: {chapter_index: chapter_data}
            "allQuestions": {},  # 모든 질문 통합: {questionId: {...}}
            "createdAt": now_iso,
            "updatedAt": now_iso,
            "error": None,
        }
    
    return {
        "status": "initialized",
        "lectureId": lecture_id_int,
        "totalChapters": len(chapters_info),
        "chapters": [
            {"title": ch["chapter_title"], "startPage": ch["start_page"], "endPage": ch["end_page"]}
            for ch in chapters_info
        ],
    }


async def handle_get_next_content_stage(payload: Dict[str, Any]):
    """
    다음 콘텐츠 세그먼트 하나를 반환
    질문이 나오면 waitingForAnswer = True로 설정하고 멈춤
    """
    if not isinstance(payload, dict):
        raise HTTPException(status_code=400, detail="payload must be a dictionary")
    
    lecture_id = payload.get("lectureId") or payload.get("lecture_id")
    
    if lecture_id is None:
        raise HTTPException(status_code=400, detail="payload.lectureId is required")
    
    try:
        lecture_id_int = int(lecture_id)
    except (ValueError, TypeError):
        raise HTTPException(status_code=400, detail="lectureId must be convertible to int")
    
    async with pipeline_lock:
        session = pipeline_sessions.get(lecture_id_int)
        if session is None:
            raise HTTPException(status_code=404, detail=f"lectureId {lecture_id} session not found. Call 'initialize' first.")
        
        # 답변 대기 중이면 에러
        if session.get("waitingForAnswer"):
            raise HTTPException(
                status_code=400,
                detail=f"Waiting for answer to question {session.get('currentQuestionId')}. Call 'answer_question' first."
            )
        
        current_chapter_idx = session["currentChapterIndex"]
        current_segment_idx = session["currentSegmentIndex"]
        total_chapters = session["totalChapters"]
        
        # 모든 챕터 완료 체크
        if current_chapter_idx >= total_chapters:
            return {
                "status": "completed",
                "lectureId": lecture_id_int,
                "message": "All chapters completed",
                "hasMore": False
            }
        
        # 현재 챕터 데이터 가져오기 (없으면 생성)
        chapter_data = session["generatedChapters"].get(current_chapter_idx)
        if chapter_data is None:
            # 챕터 강의 생성 (백그라운드가 아닌 동기 처리)
            chapter_info = session["chaptersInfo"][current_chapter_idx]
            original_pdf_path = session["pdfPath"]
            
            print(f"[get_next_content] 챕터 {current_chapter_idx} 생성 중: {chapter_info['chapter_title']}")
            chapter_data = await asyncio.to_thread(
                generate_single_chapter,
                original_pdf_path,
                chapter_info,
                current_chapter_idx
            )
            session["generatedChapters"][current_chapter_idx] = chapter_data
            
            # 질문들을 allQuestions에 추가
            for qid, qmeta in chapter_data.get("questions", {}).items():
                session["allQuestions"][qid] = {
                    **qmeta,
                    "chapterIndex": current_chapter_idx,
                    "chapterTitle": chapter_info["chapter_title"],
                    "pdfPath": chapter_data["pdfPath"],  # 분할된 챕터 PDF 경로
                    "answered": False,
                    "answer": None,
                    "supplementary": None,
                }
            
            session["updatedAt"] = datetime.utcnow().isoformat()
        
        # 다음 세그먼트 가져오기
        segment, next_segment_idx = get_next_segment(chapter_data, current_segment_idx)
        
        if segment is None:
            # 현재 챕터 끝 → 다음 챕터로
            session["currentChapterIndex"] = current_chapter_idx + 1
            session["currentSegmentIndex"] = 0
            session["updatedAt"] = datetime.utcnow().isoformat()
            pipeline_sessions[lecture_id_int] = session
            
            # 다음 챕터가 있는지 확인
            if session["currentChapterIndex"] >= total_chapters:
                return {
                    "status": "completed",
                    "lectureId": lecture_id_int,
                    "message": "All chapters completed",
                    "hasMore": False
                }
            else:
                # 다음 챕터의 첫 세그먼트를 반환하도록 재귀 호출
                return await handle_get_next_content_stage(payload)
        
        # 세그먼트가 있음
        session["currentSegmentIndex"] = next_segment_idx
        
        if segment["type"] == "question":
            # 질문 세그먼트 → 대기 상태로 전환
            question_id = segment["questionId"]
            session["waitingForAnswer"] = True
            session["currentQuestionId"] = question_id
            session["updatedAt"] = datetime.utcnow().isoformat()
            pipeline_sessions[lecture_id_int] = session
            
            return {
                "status": "question",
                "lectureId": lecture_id_int,
                "contentType": "SCRIPT",
                "contentData": segment["question"],
                "aiQuestionId": question_id,
                "chapterTitle": chapter_data["chapterTitle"],
                "hasMore": True,
                "waitingForAnswer": True
            }
        else:
            # 일반 스크립트 세그먼트
            session["updatedAt"] = datetime.utcnow().isoformat()
            pipeline_sessions[lecture_id_int] = session
            
            return {
                "status": "content",
                "lectureId": lecture_id_int,
                "contentType": "SCRIPT",
                "contentData": segment["content"],
                "chapterTitle": chapter_data["chapterTitle"],
                "hasMore": True,
                "waitingForAnswer": False
            }


async def handle_get_session_stage(payload: Dict[str, Any]):
    """현재 저장된 파이프라인 세션 정보를 반환"""
    if not isinstance(payload, dict):
        raise HTTPException(status_code=400, detail="payload must be a dictionary")

    lecture_id = payload.get("lectureId") or payload.get("lecture_id")
    if lecture_id is None:
        raise HTTPException(status_code=400, detail="payload.lectureId is required")

    try:
        lecture_id_int = int(lecture_id)
    except (ValueError, TypeError):
        raise HTTPException(status_code=400, detail="lectureId must be convertible to int")

    async with pipeline_lock:
        session = pipeline_sessions.get(lecture_id_int)

    if session is None:
        raise HTTPException(status_code=404, detail=f"lectureId {lecture_id} session not found")

    return {
        "status": "session",
        "lectureId": lecture_id_int,
        "serviceStatus": session.get("status", "unknown"),
        "chapters": session.get("chapters"),
        "questions": session.get("questions"),
        "lastAnswerMeta": session.get("lastAnswerMeta"),
        "createdAt": session.get("createdAt"),
        "updatedAt": session.get("updatedAt"),
        "error": session.get("error"),
    }


class PipelineCancelledException(Exception):
    pass

def check_cancellation(lecture_id: int):
    """세션 상태가 cancelled인지 확인하고 예외 발생"""
    # 비동기 락 없이 읽기 (간단한 dict 조회라 큰 문제 없음, 엄밀하려면 lock 필요하지만 성능 고려)
    session = pipeline_sessions.get(lecture_id)
    if session and session.get("status") == "cancelled":
        print(f"[pipeline] 작업 취소 감지됨: lecture_id={lecture_id}")
        raise PipelineCancelledException(f"Pipeline cancelled for lecture {lecture_id}")


async def run_ai_pipeline_and_callback(
    lecture_id: int,
    pdf_path: str
):
    """백그라운드에서 파이프라인을 실행하고 완료 후 웹훅 호출"""
    # Spring Boot 서버 URL (환경변수 또는 기본값)
    spring_boot_base_url = os.getenv("SPRING_BOOT_BASE_URL", "http://127.0.0.1:8080")
    # Spring Boot의 실제 웹훅 URL: /api/ai/callback/lectures/{lectureId}
    # PathVariable은 lectureId (camelCase)이지만, payload에서는 lecture_id (snake_case) 사용
    webhook_url = f"{spring_boot_base_url}/api/ai/callback/lectures/{lecture_id}"
    
    try:
        print(f"[background] 파이프라인 시작: lecture_id={lecture_id}, pdf_path={pdf_path}")
        
        # 취소 체크 콜백
        cancellation_callback = lambda: check_cancellation(lecture_id)

        # 파이프라인 실행 (동기 함수를 비동기로 실행)
        # skip_qa=True로 설정하여 Q&A 처리 건너뛰기 (API 호출 시)
        # cancellation_callback 전달 (integration.py가 지원해야 함)
        result = await asyncio.to_thread(run_full_pipeline, pdf_path, True, cancellation_callback)
        
        # result는 (chapters_info, lecture_results) 튜플
        chapters_info, lecture_results = result
        
        print(f"[background] 파이프라인 완료: lecture_id={lecture_id}, 챕터 수: {len(chapters_info)}")
        
        # Spring Boot가 기대하는 형식: List<AiResponseDto>
        ai_response_list = convert_to_ai_response_dto(chapters_info, lecture_results)
        
        # ✅ [추가] main-service와 약속한 비밀키 헤더
        # 환경변수에서 비밀키 읽기 (기본값: YOUR_SUPER_SECRET_AI_KEY_12345)
        ai_secret_key = os.getenv("AI_SECRET_KEY", "YOUR_SUPER_SECRET_AI_KEY_12345")
        headers = {
            "Content-Type": "application/json",
            "X-AI-SECRET-KEY": ai_secret_key  # yml과 동일한 키
        }
        
        # 웹훅 호출 (성공)
        # 리다이렉트를 따라가도록 설정 (follow_redirects=True)
        # Spring Boot의 SecurityConfig가 수정되지 않은 경우를 대비
        async with httpx.AsyncClient(timeout=30.0, follow_redirects=True) as client:
            # ✅ 헤더를 포함하여 콜백 전송
            response = await client.post(
                webhook_url,
                json=ai_response_list,  # Spring Boot는 List<AiResponseDto>를 기대
                headers=headers
            )
            
            # 302 리다이렉트가 발생했는지 확인 (리다이렉트를 따라간 후 최종 응답 확인)
            if response.status_code >= 400:
                error_msg = (
                    f"웹훅 호출 실패: status={response.status_code}\n"
                    f"응답 내용: {response.text[:500] if hasattr(response, 'text') else 'N/A'}\n\n"
                    f"⚠️ Spring Boot의 SecurityConfig에서 `/api/ai/callback/**` 경로를 permitAll()에 추가해야 합니다.\n"
                    f"현재 웹훅 엔드포인트가 Spring Security에 의해 보호되어 있을 수 있습니다."
                )
                print(f"[ERROR] {error_msg}")
                # 200-299 범위가 아니면 에러로 처리하지 않고 경고만 출력
                if response.status_code >= 500:
                    raise HTTPException(status_code=502, detail=error_msg)
                else:
                    print(f"[WARNING] 웹훅 호출이 비정상 응답을 받았지만 계속 진행합니다: status={response.status_code}")
            
            print(f"[background] 웹훅 호출 완료: lecture_id={lecture_id}, status={response.status_code}")
            
    except PipelineCancelledException:
        print(f"[background] 파이프라인 실행 취소: lecture_id={lecture_id}")
        # 취소된 경우 웹훅을 호출하지 않거나, 취소 상태를 알리는 웹훅을 호출할 수 있음
        # 여기서는 그냥 종료
        return

    except Exception as e:
        error_trace = traceback.format_exc()
        print(f"[background] 파이프라인 실행 실패: lecture_id={lecture_id}")
        print(f"에러: {type(e).__name__}: {str(e)}")
        print(error_trace)
        logger.error(f"파이프라인 실행 실패: lecture_id={lecture_id}\n{error_trace}")
        
        # 웹훅 호출 (실패) - Spring Boot는 실패 시에도 빈 리스트를 받을 수 있음
        try:
            # ✅ [추가] main-service와 약속한 비밀키 헤더
            ai_secret_key = os.getenv("AI_SECRET_KEY", "YOUR_SUPER_SECRET_AI_KEY_12345")
            headers = {
                "Content-Type": "application/json",
                "X-AI-SECRET-KEY": ai_secret_key  # yml과 동일한 키
            }
            
            # 실패 시 빈 리스트 전송 (Spring Boot가 에러를 감지하도록)
            async with httpx.AsyncClient(timeout=30.0, follow_redirects=False) as client:
                response = await client.post(
                    webhook_url,
                    json=[],  # 빈 리스트로 실패를 알림
                    headers=headers
                )
                print(f"[background] 웹훅 호출 (에러): lecture_id={lecture_id}, status={response.status_code}")
        except Exception as webhook_error:
            print(f"[background] 웹훅 호출 실패: {str(webhook_error)}")
            logger.error(f"웹훅 호출 실패: {str(webhook_error)}")


def run_ai_pipeline_and_callback_sync(lecture_id: int, pdf_path: str):
    """
    BackgroundTasks용 동기 래퍼 함수
    FastAPI 공식 문서: BackgroundTasks는 동기 함수를 받아야 함
    """
    asyncio.run(run_ai_pipeline_and_callback(lecture_id, pdf_path))


@router.get("/dispatch")
async def dispatch_get(request: Request):
    """GET 요청 핸들러 - 정보 제공용"""
    print(f"[INFO] GET 요청 수신: {request.url}")
    return {
        "message": "이 엔드포인트는 POST 요청을 사용하세요.",
        "method": "POST",
        "endpoint": "/api/delegator/dispatch",
        "example": {
            "stage": "run_all",
            "payload": {
                "lectureId": 1,
                "pdf_path": "C:\\...\\ai-service\\uploads\\file.pdf"
            }
        }
    }


@router.post("/dispatch")
async def dispatch(req: DelegatorDispatchRequest = None, background_tasks: BackgroundTasks = None):
    """
    Spring Boot에서 호출하는 엔드포인트
    - Spring Boot는 lectureId를 전달하지 않으므로, payload에서 추출하거나 다른 방법 필요
    - 현재는 payload에 lectureId가 포함되어 있다고 가정
    """
    # 요청이 None인 경우 처리
    if req is None:
        print(f"[ERROR] 요청이 None입니다. 요청 형식을 확인하세요.")
        raise HTTPException(
            status_code=400,
            detail="요청 본문이 없습니다. JSON 형식으로 stage와 payload를 전달해야 합니다."
        )

    stage = (req.stage or "run_all").lower()

    # 디버깅: 요청 내용 로그 출력
    print(f"[delegator] POST 요청 수신: stage={stage}")
    print(f"[delegator] payload 타입: {type(req.payload)}")
    print(f"[delegator] payload 내용: {req.payload}")

    # 즉시 처리 stage: answer_question, get_session, initialize, get_next_content, cancel
    if stage in {"answer_question", "get_session", "initialize", "get_next_content", "cancel"}:
        if stage == "answer_question":
            return await handle_answer_question_stage(req.payload)
        elif stage == "get_session":
            return await handle_get_session_stage(req.payload)
        elif stage == "initialize":
            return await handle_initialize_stage(req.payload)
        elif stage == "get_next_content":
            return await handle_get_next_content_stage(req.payload)
        elif stage == "cancel":
            lecture_id = req.payload.get("lectureId") or req.payload.get("lecture_id")
            if not lecture_id:
                raise HTTPException(status_code=400, detail="payload.lectureId is required")
            try:
                lecture_id_int = int(lecture_id)
            except:
                raise HTTPException(status_code=400, detail="lectureId must be int")
            
            async with pipeline_lock:
                if lecture_id_int in pipeline_sessions:
                    pipeline_sessions[lecture_id_int]["status"] = "cancelled"
                    print(f"[delegator] 취소 요청 접수: lecture_id={lecture_id_int}")
                    return {"status": "cancelled", "message": "Cancellation requested."}
                else:
                    return {"status": "not_found", "message": "Session not found."}

    if background_tasks is None:
        from fastapi import BackgroundTasks as BGT
        background_tasks = BGT()

    if not isinstance(req.payload, dict):
        print(f"[ERROR] payload가 dict가 아닙니다: {type(req.payload)}")
        raise HTTPException(status_code=400, detail="payload must be a dictionary")

    pdf_path = req.payload.get("pdf_path")
    lecture_id = req.payload.get("lectureId") or req.payload.get("lecture_id")

    print(f"[delegator] pdf_path: {pdf_path}")
    print(f"[delegator] lecture_id: {lecture_id}")
    print(f"[delegator] payload의 모든 키: {list(req.payload.keys()) if isinstance(req.payload, dict) else 'N/A'}")

    if not pdf_path:
        print(f"[ERROR] pdf_path가 없습니다. payload: {req.payload}")
        raise HTTPException(status_code=400, detail="payload.pdf_path is required")

    if not lecture_id:
        print(f"[ERROR] lectureId 또는 lecture_id가 없습니다. payload: {req.payload}")
        print(f"[ERROR] payload의 모든 키: {list(req.payload.keys()) if isinstance(req.payload, dict) else 'N/A'}")
        raise HTTPException(
            status_code=400,
            detail=f"payload.lectureId 또는 payload.lecture_id가 필요합니다. 현재 payload: {req.payload}. Spring Boot에서 lectureId를 전달해야 합니다."
        )

    file_path = Path(pdf_path)
    
    # Spring Boot 서버 경로 감지 (예: C:\dev\ai-platform-uploads\...)
    if "ai-platform-uploads" in pdf_path or ("C:\\dev\\" in pdf_path and "ai-service" not in pdf_path):
        error_msg = (
            f"❌ 잘못된 경로입니다! Spring Boot 서버의 경로를 직접 전달하고 있습니다.\n\n"
            f"❌ 현재 전달된 경로: {pdf_path}\n\n"
            f"✅ 해결 방법:\n"
            f"1. Spring Boot에서 먼저 `/api/files/upload`를 호출하세요.\n"
            f"2. 업로드 응답의 `path`를 받으세요 (예: C:\\...\\ai-service\\uploads\\...)\n"
            f"3. 그 `path`를 `payload.pdf_path`로 전달하세요.\n\n"
            f"⚠️ Spring Boot 서버 경로(`C:\\dev\\...`)는 FastAPI에서 접근할 수 없습니다!"
        )
        print(f"[ERROR] {error_msg}")
        raise HTTPException(status_code=400, detail=error_msg)
    
    if not file_path.exists():
        error_msg = (
            f"PDF 파일을 찾을 수 없습니다.\n"
            f"경로: {pdf_path}\n"
            f"절대 경로: {file_path.resolve()}\n"
            f"파일이 존재하는지 확인해주세요.\n\n"
            f"⚠️ 경로가 `/api/files/upload` 응답의 `path`인지 확인하세요.\n"
            f"⚠️ Spring Boot 서버의 경로(`C:\\dev\\...`)를 직접 전달하면 안 됩니다!"
        )
        print(f"[ERROR] {error_msg}")
        raise HTTPException(status_code=404, detail=error_msg)
    
    if not file_path.is_file():
        error_msg = f"경로가 파일이 아닙니다: {pdf_path}"
        print(f"[ERROR] {error_msg}")
        raise HTTPException(status_code=400, detail=error_msg)

    try:
        lecture_id = int(lecture_id)
    except (ValueError, TypeError):
        raise HTTPException(status_code=400, detail="lecture_id는 정수여야 합니다.")

    if stage == "generate_script":
        now_iso = datetime.utcnow().isoformat()
        async with pipeline_lock:
            existing_session = pipeline_sessions.get(lecture_id)
            pipeline_sessions[lecture_id] = {
                "lectureId": lecture_id,
                "pdfPath": pdf_path,
                "status": "preparing",
                "chapters": existing_session.get("chapters", []) if existing_session else [],
                "questions": existing_session.get("questions", {}) if existing_session else {},
                "createdAt": existing_session.get("createdAt", now_iso) if existing_session else now_iso,
                "updatedAt": now_iso,
                "error": None,
            }

        if background_tasks is None:
            from fastapi import BackgroundTasks as BGT
            background_tasks = BGT()

        background_tasks.add_task(start_generate_script_background_sync, lecture_id, pdf_path)
        print(f"[delegator] generate_script 비동기 시작: lecture_id={lecture_id}")
        return {
            "status": "preparing",
            "lectureId": lecture_id,
            "message": "script generation started (async). poll get_session to check readiness."
        }

    if stage not in {"run_all", "start", "run_all_with_callback", "pdf_processing"}:
        raise HTTPException(
            status_code=400,
            detail=f"지원하지 않는 stage입니다: {stage}. 사용 가능한 stage: run_all, pdf_processing, generate_script, answer_question, get_session"
        )

    background_tasks.add_task(
        run_ai_pipeline_and_callback_sync,
        lecture_id,
        pdf_path
    )

    print(f"[delegator] 작업 시작: lecture_id={lecture_id}, pdf_path={pdf_path}")

    return {
        "status": "processing",
        "message": "AI content generation started."
    }
