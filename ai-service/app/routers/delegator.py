from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
import httpx
import os
from dotenv import load_dotenv
from ai_agent.Lecture_Agent.integration import main as run_full_pipeline

# .env 파일 로드
load_dotenv()

class DelegatorDispatchRequest(BaseModel):
    stage: str
    payload: dict

router = APIRouter(prefix="/api/delegator", tags=["delegator"])

@router.post("/generated-content")
async def generated_content(req: DelegatorDispatchRequest):
    # 유효성 검사
    if not isinstance(req.payload, dict):
        raise HTTPException(status_code=400, detail="payload must be a dictionary")

    pdf_path = req.payload.get("pdf_path")
    lecture_id = req.payload.get("lecture_id")
    
    if not pdf_path:
        raise HTTPException(status_code=400, detail="payload.pdf_path is required")
    if not lecture_id:
        raise HTTPException(status_code=400, detail="payload.lecture_id is required")

    # Spring Boot 서버 URL (환경변수 또는 기본값)
    spring_boot_base_url = os.getenv("SPRING_BOOT_BASE_URL", "http://127.0.0.1:8080")
    spring_boot_url = f"{spring_boot_base_url}/lectures/{lecture_id}/generated-content"
    
    # 디버깅용 로그 (선택사항)
    print(f"[delegator] Spring Boot URL: {spring_boot_url}")

    try:
        # 1. 파이프라인 실행
        result = run_full_pipeline(pdf_path)
        
        # 2. 결과를 Spring Boot로 전송
        # TODO: result 구조에 맞게 데이터 변환 필요
        # 현재 integration.main은 None을 반환하므로, 
        # 나중에 결과를 반환하도록 수정 필요
        payload_data = {
            "status": "completed",
            "pdf_path": pdf_path,
            "result": result  # result가 None일 수 있음
        }
        
        async with httpx.AsyncClient(timeout=30.0) as client:
            response = await client.post(
                spring_boot_url,
                json=payload_data,
                headers={"Content-Type": "application/json"}
            )
            response.raise_for_status()
        
        return {
            "status": "ok",
            "result": result,
            "spring_boot_response": response.json() if response.status_code == 200 else None
        }
    except httpx.HTTPError as e:
        raise HTTPException(
            status_code=502,
            detail=f"Spring Boot 서버 호출 실패: {str(e)}"
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
