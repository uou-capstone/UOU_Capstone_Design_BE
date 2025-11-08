from fastapi import APIRouter
from pydantic import BaseModel, Field
from app.services.qa_service import evaluate_answer


class QAEvaluateRequest(BaseModel):
    original_q: str = Field(..., description="원래 질문")
    user_answer: str = Field(..., description="사용자 답변")
    pdf_path: str = Field(..., description="로컬 PDF 파일 경로")


router = APIRouter(prefix="/api/qa", tags=["qa"])


@router.post("/evaluate")
def evaluate(req: QAEvaluateRequest):
    text = evaluate_answer(req.original_q, req.user_answer, req.pdf_path)
    return {"supplementary_explanation": text}


