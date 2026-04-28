import asyncio

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.services.pdf_splitter import analyze_and_split
from app.core.path_validator import validate_pdf_path


class PdfAnalyzeRequest(BaseModel):
    pdf_path: str = Field(..., description="로컬 PDF 파일 경로")


router = APIRouter(prefix="/api/pdf", tags=["pdf"])


@router.post("/analyze")
async def analyze_pdf(req: PdfAnalyzeRequest):
    safe_path = validate_pdf_path(req.pdf_path)
    try:
        result = await asyncio.to_thread(analyze_and_split, safe_path)
        return {"items": result}
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail=f"파일을 찾을 수 없습니다: {req.pdf_path}")
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"PDF 분석 실패: {str(e)}")


