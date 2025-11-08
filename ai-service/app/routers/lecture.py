from fastapi import APIRouter
from pydantic import BaseModel, Field
from app.services.lecture_gen import generate_markdown


class LectureGenerateRequest(BaseModel):
    chapter_title: str = Field(..., description="챕터 제목")
    pdf_path: str = Field(..., description="로컬 PDF 파일 경로")


router = APIRouter(prefix="/api/lecture", tags=["lecture"])


@router.post("/generate")
def generate(req: LectureGenerateRequest):
    markdown_text = generate_markdown(req.chapter_title, req.pdf_path)
    return {"chapter_title": req.chapter_title, "content": markdown_text}


