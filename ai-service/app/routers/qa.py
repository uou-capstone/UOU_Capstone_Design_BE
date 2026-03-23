"""
QA Router  [v2 Classic Track]

개별 Q&A 평가 엔드포인트.
사용자의 답변을 평가하고 모범 답안을 반환합니다.
"""
import json
import asyncio

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.services.qa_service import evaluate_answer

router = APIRouter(prefix="/api/v2/qa", tags=["[v2] QA"])


class QAEvaluateRequest(BaseModel):
    original_q: str = Field(..., description="원본 질문")
    user_answer: str = Field(..., description="사용자 답변")
    pdf_path: str = Field(..., description="강의 PDF 파일 경로")


@router.post("/evaluate")
async def evaluate(req: QAEvaluateRequest):
    """
    사용자 답변을 평가하고 모범 답안/피드백을 반환합니다.

    Returns:
        평가 결과 JSON (score, feedback, model_answer 등)
    """
    try:
        text = await asyncio.to_thread(evaluate_answer, req.original_q, req.user_answer, req.pdf_path)
        return json.loads(text)
    except json.JSONDecodeError:
        # LLM이 JSON이 아닌 텍스트를 반환한 경우 래핑
        return {"feedback": text}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"QA 평가 실패: {str(e)}")
