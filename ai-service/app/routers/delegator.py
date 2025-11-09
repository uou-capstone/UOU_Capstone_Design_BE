from fastapi import APIRouter, HTTPException, BackgroundTasks
from pydantic import BaseModel, Field
import traceback
import logging
import asyncio
import httpx
import os
from pathlib import Path
from ai_agent.Lecture_Agent.integration import main as run_full_pipeline

logger = logging.getLogger(__name__)

# ✅ payload를 위한 구체적인 모델 정의
class DispatchPayload(BaseModel):
    pdf_path: str = Field(..., description="처리할 PDF 파일 경로")
    lectureId: int = Field(..., description="콜백을 위한 강의 ID")

class DelegatorDispatchRequest(BaseModel):
    stage: str
    payload: DispatchPayload  # 👈 dict 대신 구체적인 모델 사용

router = APIRouter(prefix="/api/delegator", tags=["delegator"])


async def run_ai_pipeline_and_callback(
    lecture_id: int,
    pdf_path: str
):
    """백그라운드에서 파이프라인을 실행하고 완료 후 웹훅 호출"""
    # Spring Boot 서버 URL (환경변수 또는 기본값)
    spring_boot_base_url = os.getenv("SPRING_BOOT_BASE_URL", "https://michal-unvulnerable-benita.ngrok-free.dev")
    webhook_url = f"{spring_boot_base_url}/api/ai/callback/{lecture_id}"
    
    try:
        print(f"[background] 파이프라인 시작: lecture_id={lecture_id}, pdf_path={pdf_path}")
        
        # 파이프라인 실행
        result = await asyncio.to_thread(run_full_pipeline, pdf_path)
        
        print(f"[background] 파이프라인 완료: lecture_id={lecture_id}")
        
        # 웹훅 호출 (성공)
        webhook_payload = {
            "lectureId": lecture_id,
            "status": "completed",
            "result": result,
            "pdf_path": pdf_path
        }
        
        async with httpx.AsyncClient(timeout=30.0) as client:
            response = await client.post(
                webhook_url,
                json=webhook_payload,
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
        
        # 웹훅 호출 (실패)
        try:
            webhook_payload = {
                "lectureId": lecture_id,
                "status": "failed",
                "error": f"{type(e).__name__}: {str(e)}",
                "pdf_path": pdf_path
            }
            
            async with httpx.AsyncClient(timeout=30.0) as client:
                response = await client.post(
                    webhook_url,
                    json=webhook_payload,
                    headers={"Content-Type": "application/json"}
                )
                print(f"[background] 웹훅 호출 (에러): lecture_id={lecture_id}, status={response.status_code}")
        except Exception as webhook_error:
            print(f"[background] 웹훅 호출 실패: {str(webhook_error)}")
            logger.error(f"웹훅 호출 실패: {str(webhook_error)}")


@router.post("/dispatch")
async def dispatch(req: DelegatorDispatchRequest, background_tasks: BackgroundTasks):
    # ✅ Pydantic이 자동으로 유효성 검사를 해주므로 수동 검사 코드 삭제
    pdf_path = req.payload.pdf_path  # 👈 모델에서 직접 접근
    lecture_id = req.payload.lectureId  # 👈 모델에서 직접 접근

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

    # ✅ 백그라운드 작업 시작
    background_tasks.add_task(
        run_ai_pipeline_and_callback,
        lecture_id,
        pdf_path
    )
    
    print(f"[delegator] 작업 시작: lecture_id={lecture_id}, pdf_path={pdf_path}")
    
    # ✅ 즉시 응답 반환
    return {
        "status": "processing",
        "message": "AI content generation started."
    }
