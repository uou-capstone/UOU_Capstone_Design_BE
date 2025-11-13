from fastapi import APIRouter
from pydantic import BaseModel, Field
import asyncio
from app.services.lecture_gen import generate_markdown


class LectureGenerateRequest(BaseModel):
    chapter_title: str = Field(..., description="챕터 제목")
    pdf_path: str = Field(..., description="로컬 PDF 파일 경로")


router = APIRouter(prefix="/api/lectures", tags=["lecture"])


@router.post("/generate")
async def generate(req: LectureGenerateRequest):
    # 동기 함수를 스레드 풀에서 실행하여 블로킹 방지
    markdown_text = await asyncio.to_thread(generate_markdown, req.chapter_title, req.pdf_path)
    return {"chapter_title": req.chapter_title, "content": markdown_text}


