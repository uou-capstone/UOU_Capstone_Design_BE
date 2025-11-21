from fastapi import APIRouter
from pydantic import BaseModel, Field
import asyncio
from app.services.pdf_splitter import analyze_and_split


class PdfAnalyzeRequest(BaseModel):
    pdf_path: str = Field(..., description="로컬 PDF 파일 경로")


router = APIRouter(prefix="/api/pdf", tags=["pdf"])


@router.post("/analyze")
async def analyze_pdf(req: PdfAnalyzeRequest):
    # 동기 함수를 스레드 풀에서 실행하여 블로킹 방지
    result = await asyncio.to_thread(analyze_and_split, req.pdf_path)
    return {"items": result}


