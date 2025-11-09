from fastapi import APIRouter, HTTPException, BackgroundTasks
from pydantic import BaseModel
import traceback
import logging
import asyncio
import httpx
import os
from pathlib import Path
from dotenv import load_dotenv
from ai_agent.Lecture_Agent.integration import main as run_full_pipeline

# .env 파일 로드
load_dotenv()

logger = logging.getLogger(__name__)

class DelegatorDispatchRequest(BaseModel):
    stage: str
    payload: dict

router = APIRouter(prefix="/api/delegator", tags=["delegator"])


def convert_to_ai_response_dto(chapters_info, lecture_results):
    """
    integration.py의 결과를 Spring Boot의 AiResponseDto 형식으로 변환
    
    Args:
        chapters_info: List[Tuple[str, str]] - (chapter_title, pdf_path)
        lecture_results: List[Dict[str, str]] - [{chapter_title: explanation}, ...]
    
    Returns:
        List[Dict] - AiResponseDto 형식의 리스트
    """
    ai_responses = []
    
    for (chapter_title, pdf_path), lecture_dict in zip(chapters_info, lecture_results):
        explanation = lecture_dict.get(chapter_title, "")
        
        # SCRIPT 타입으로 변환 (강의 설명)
        ai_responses.append({
            "contentType": "SCRIPT",
            "contentData": explanation,
            "materialReferences": pdf_path  # PDF 경로를 참조로 저장
        })
    
    return ai_responses


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
        
        # 파이프라인 실행 (동기 함수를 비동기로 실행)
        # skip_qa=True로 설정하여 Q&A 처리 건너뛰기 (API 호출 시)
        result = await asyncio.to_thread(run_full_pipeline, pdf_path, True)
        
        # result는 (chapters_info, lecture_results) 튜플
        chapters_info, lecture_results = result
        
        print(f"[background] 파이프라인 완료: lecture_id={lecture_id}, 챕터 수: {len(chapters_info)}")
        
        # Spring Boot가 기대하는 형식: List<AiResponseDto>
        ai_response_list = convert_to_ai_response_dto(chapters_info, lecture_results)
        
        # 웹훅 호출 (성공)
        async with httpx.AsyncClient(timeout=30.0) as client:
            response = await client.post(
                webhook_url,
                json=ai_response_list,  # Spring Boot는 List<AiResponseDto>를 기대
                headers={"Content-Type": "application/json"}
            )
            response.raise_for_status()
            print(f"[background] 웹훅 호출 성공: lecture_id={lecture_id}, status={response.status_code}")
            
    except Exception as e:
        error_trace = traceback.format_exc()
        print(f"[background] 파이프라인 실행 실패: lecture_id={lecture_id}")
        print(f"에러: {type(e).__name__}: {str(e)}")
        print(error_trace)
        logger.error(f"파이프라인 실행 실패: lecture_id={lecture_id}\n{error_trace}")
        
        # 웹훅 호출 (실패) - Spring Boot는 실패 시에도 빈 리스트를 받을 수 있음
        try:
            # 실패 시 빈 리스트 전송 (Spring Boot가 에러를 감지하도록)
            async with httpx.AsyncClient(timeout=30.0) as client:
                response = await client.post(
                    webhook_url,
                    json=[],  # 빈 리스트로 실패를 알림
                    headers={"Content-Type": "application/json"}
                )
                print(f"[background] 웹훅 호출 (에러): lecture_id={lecture_id}, status={response.status_code}")
        except Exception as webhook_error:
            print(f"[background] 웹훅 호출 실패: {str(webhook_error)}")
            logger.error(f"웹훅 호출 실패: {str(webhook_error)}")


@router.post("/dispatch")
async def dispatch(req: DelegatorDispatchRequest, background_tasks: BackgroundTasks):
    """
    Spring Boot에서 호출하는 엔드포인트
    - Spring Boot는 lectureId를 전달하지 않으므로, payload에서 추출하거나 다른 방법 필요
    - 현재는 payload에 lectureId가 포함되어 있다고 가정
    """
    # 유효성 검사
    if not isinstance(req.payload, dict):
        raise HTTPException(status_code=400, detail="payload must be a dictionary")

    pdf_path = req.payload.get("pdf_path")
    lecture_id = req.payload.get("lecture_id")  # 엔드포인트는 lecture_id (snake_case)
    
    if not pdf_path:
        raise HTTPException(status_code=400, detail="payload.pdf_path is required")
    
    # lecture_id가 없으면 에러 (Spring Boot가 전달해야 함)
    if not lecture_id:
        raise HTTPException(
            status_code=400, 
            detail="payload.lecture_id가 필요합니다. Spring Boot에서 lecture_id를 전달해야 합니다."
        )

    # 파일 경로 검증
    file_path = Path(pdf_path)
    if not file_path.exists():
        error_msg = (
            f"PDF 파일을 찾을 수 없습니다.\n"
            f"경로: {pdf_path}\n"
            f"절대 경로: {file_path.resolve()}\n"
            f"파일이 존재하는지 확인해주세요."
        )
        print(f"[ERROR] {error_msg}")
        raise HTTPException(status_code=404, detail=error_msg)
    
    if not file_path.is_file():
        error_msg = f"경로가 파일이 아닙니다: {pdf_path}"
        print(f"[ERROR] {error_msg}")
        raise HTTPException(status_code=400, detail=error_msg)

    # lectureId를 int로 변환
    try:
        lecture_id = int(lecture_id)
    except (ValueError, TypeError):
        raise HTTPException(status_code=400, detail="lecture_id는 정수여야 합니다.")

    # ✅ 백그라운드 작업 시작
    background_tasks.add_task(
        run_ai_pipeline_and_callback,
        lecture_id,
        pdf_path
    )
    
    print(f"[delegator] 작업 시작: lecture_id={lecture_id}, pdf_path={pdf_path}")
    
    # ✅ 즉시 응답 반환 (Spring Boot는 응답을 기다리지 않음)
    return {
        "status": "processing",
        "message": "AI content generation started."
    }
