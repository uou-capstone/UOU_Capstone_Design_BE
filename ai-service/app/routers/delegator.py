from fastapi import APIRouter, HTTPException, BackgroundTasks
from pydantic import BaseModel
import traceback
import logging
import asyncio
import httpx
import os
from pathlib import Path
from ai_agent.Lecture_Agent.integration import main as run_full_pipeline

logger = logging.getLogger(__name__)

class DelegatorDispatchRequest(BaseModel):
    stage: str
    payload: dict

router = APIRouter(prefix="/api/delegator", tags=["delegator"])


async def run_pipeline_background(
    pdf_path: str,
    lecture_id: str,
    webhook_url: str
):
    """백그라운드에서 파이프라인을 실행하고 완료 후 웹훅 호출"""
    try:
        print(f"[background] 파이프라인 시작: lecture_id={lecture_id}, pdf_path={pdf_path}")
        
        # 파이프라인 실행
        result = await asyncio.to_thread(run_full_pipeline, pdf_path)
        
        print(f"[background] 파이프라인 완료: lecture_id={lecture_id}")
        
        # 웹훅 호출 (성공)
        webhook_payload = {
            "lecture_id": lecture_id,
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
                "lecture_id": lecture_id,
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
    # 유효성 검사
    if not isinstance(req.payload, dict):
        raise HTTPException(status_code=400, detail="payload must be a dictionary")

    pdf_path = req.payload.get("pdf_path")
    lecture_id = req.payload.get("lecture_id")
    webhook_url = req.payload.get("webhook_url")
    
    if not pdf_path:
        raise HTTPException(status_code=400, detail="payload.pdf_path is required")
    if not lecture_id:
        raise HTTPException(status_code=400, detail="payload.lecture_id is required")
    if not webhook_url:
        raise HTTPException(status_code=400, detail="payload.webhook_url is required")

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

    # 백그라운드 작업 추가
    background_tasks.add_task(
        run_pipeline_background,
        str(pdf_path),
        str(lecture_id),
        str(webhook_url)
    )
    
    print(f"[delegator] 작업 시작: lecture_id={lecture_id}, webhook_url={webhook_url}")
    
    # 즉시 응답 반환
    return {
        "status": "accepted",
        "message": "작업이 시작되었습니다",
        "lecture_id": lecture_id
    }
