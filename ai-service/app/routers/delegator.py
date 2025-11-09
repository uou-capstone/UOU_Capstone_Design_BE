from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
import traceback
import logging
from pathlib import Path
from ai_agent.Lecture_Agent.integration import main as run_full_pipeline

logger = logging.getLogger(__name__)

class DelegatorDispatchRequest(BaseModel):
    stage: str
    payload: dict

router = APIRouter(prefix="/api/delegator", tags=["delegator"])

@router.post("/dispatch")
def dispatch(req: DelegatorDispatchRequest):
    # 유효성 검사
    if not isinstance(req.payload, dict):
        raise HTTPException(status_code=400, detail="payload must be a dictionary")

    pdf_path = req.payload.get("pdf_path")
    if not pdf_path:
        raise HTTPException(status_code=400, detail="payload.pdf_path is required")

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

    # ✅ 현재는 pdf_path만 사용 stage는 사용하지 않음
    try:
        print(f"[delegator] 파이프라인 시작: pdf_path={pdf_path}")
        print(f"[delegator] 파일 존재 확인: {file_path.exists()}")
        result = run_full_pipeline(str(pdf_path))
        print(f"[delegator] 파이프라인 완료")
        return {"status": "ok", "result": result}
    except FileNotFoundError as e:
        error_trace = traceback.format_exc()
        print(f"[ERROR] 파일을 찾을 수 없습니다: {pdf_path}")
        print(error_trace)
        logger.error(f"파일을 찾을 수 없습니다: {pdf_path}\n{error_trace}")
        raise HTTPException(status_code=404, detail=f"PDF 파일을 찾을 수 없습니다: {str(e)}")
    except Exception as e:
        error_trace = traceback.format_exc()
        print(f"[ERROR] 파이프라인 실행 중 에러 발생:")
        print(f"에러 타입: {type(e).__name__}")
        print(f"에러 메시지: {str(e)}")
        print(f"전체 traceback:\n{error_trace}")
        logger.error(f"파이프라인 실행 중 에러 발생:\n{error_trace}")
        # 에러 메시지가 너무 길면 잘라서 전송
        error_detail = f"{type(e).__name__}: {str(e)}"
        if len(error_trace) > 1000:
            error_detail += f"\n\n(전체 traceback은 서버 로그를 확인하세요)"
        raise HTTPException(
            status_code=500, 
            detail=error_detail
        )
