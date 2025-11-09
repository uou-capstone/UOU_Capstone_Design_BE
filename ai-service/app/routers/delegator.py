from fastapi import APIRouter, HTTPException, BackgroundTasks
from pydantic import BaseModel, Field
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

# ✅ payload를 위한 구체적인 모델 정의
class DispatchPayload(BaseModel):
    pdf_path: str = Field(..., description="처리할 PDF 파일 경로")
    lectureId: int = Field(..., description="콜백을 위한 강의 ID")

class DelegatorDispatchRequest(BaseModel):
    stage: str
    payload: DispatchPayload  # 👈 dict 대신 구체적인 모델 사용

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
