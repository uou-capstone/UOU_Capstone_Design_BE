from fastapi import APIRouter
from pydantic import BaseModel, Field
from app.services.pdf_splitter import analyze_and_split


class PdfAnalyzeRequest(BaseModel):
    pdf_path: str = Field(..., description="로컬 PDF 파일 경로")


router = APIRouter(prefix="/api/pdf", tags=["pdf"])


@router.post("/analyze")
def analyze_pdf(req: PdfAnalyzeRequest):
    result = analyze_and_split(req.pdf_path)
    return {"items": result}


