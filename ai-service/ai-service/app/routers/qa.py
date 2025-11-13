from fastapi import APIRouter
from pydantic import BaseModel, Field
import asyncio
from app.services.qa_service import evaluate_answer


class QAEvaluateRequest(BaseModel):
    original_q: str = Field(..., description="원래 질문")
    user_answer: str = Field(..., description="사용자 답변")
    pdf_path: str = Field(..., description="로컬 PDF 파일 경로")


router = APIRouter(prefix="/api/qa", tags=["qa"])


@router.post("/evaluate")
async def evaluate(req: QAEvaluateRequest):
    # 동기 함수를 스레드 풀에서 실행하여 블로킹 방지
    text = await asyncio.to_thread(evaluate_answer, req.original_q, req.user_answer, req.pdf_path)
    return {"supplementary_explanation": text}


