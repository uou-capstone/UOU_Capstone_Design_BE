from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from ai_agent.Lecture_Agent.integration import main as run_full_pipeline

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

    # ✅ 현재는 pdf_path만 사용 stage는 사용하지 않음
    try:
        result = run_full_pipeline(req.payload.get("pdf_path"))
        return {"status": "ok", "result": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
