from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field
import asyncio
import json
from app.services.lecture_gen import generate_markdown
from ai_agent.Lecture_Agent.component.MainLectureAgent import generate_explanation_streaming
from ai_agent.Lecture_Agent.component.MainLectureAgent import LectureState


class LectureGenerateRequest(BaseModel):
    chapter_title: str = Field(..., description="챕터 제목")
    pdf_path: str = Field(..., description="로컬 PDF 파일 경로")
    md_path: str = Field(..., description="생성된 마크다운(MD) 대본 파일 경로")


router = APIRouter(prefix="/api/lectures", tags=["lecture"])


@router.post("/generate")
async def generate(req: LectureGenerateRequest):
    # 동기 함수를 스레드 풀에서 실행하여 블로킹 방지
    markdown_text = await asyncio.to_thread(generate_markdown, req.chapter_title, req.pdf_path, req.md_path)
    return {"chapter_title": req.chapter_title, "content": markdown_text}


@router.post("/generate-stream")
async def generate_stream(req: LectureGenerateRequest):
    """
    강의 설명을 생성하면서 추론 과정을 스트리밍합니다.
    Server-Sent Events (SSE) 형식으로 응답합니다.
    
    예외 처리:
    - 네트워크 오류 시 type: "error" 이벤트 전송
    - 타임아웃 설정 (기본 300초)
    - 재연결 지원 (프론트엔드에서 EventSource 재연결)
    """
    import logging
    logger = logging.getLogger(__name__)
    
    # 히스토리 저장을 위한 로그 수집
    thought_history = []
    answer_buffer = ""
    
    async def event_generator():
        nonlocal thought_history, answer_buffer
        state: LectureState = {
            "chapter_title": req.chapter_title,
            "pdf_path": req.pdf_path,
            "md_path": req.md_path,
            "explanation": ""
        }
        
        try:
            async for event in generate_explanation_streaming(state):
                # 히스토리 수집
                if event.type == "thought" and event.content:
                    thought_history.append(event.content.model_dump() if hasattr(event.content, 'model_dump') else event.content)
                elif event.type == "answer" and event.delta:
                    answer_buffer += event.delta
                
                # SSE 형식으로 전송
                data = event.model_dump_json()
                yield f"data: {data}\n\n"
            
            # 완료 시 히스토리 저장 (백그라운드)
            if thought_history:
                try:
                    # 히스토리 저장 로직 (나중에 DB 연동)
                    await save_thought_history(req.chapter_title, thought_history, answer_buffer)
                except Exception as save_error:
                    logger.warning(f"히스토리 저장 실패: {save_error}")
            
            # 완료 이벤트
            yield f"data: {json.dumps({'type': 'complete', 'message': '스트리밍 완료'}, ensure_ascii=False)}\n\n"
            
        except asyncio.TimeoutError:
            logger.error("스트리밍 타임아웃 발생")
            error_event = {
                "type": "error",
                "delta": "요청 시간이 초과되었습니다. 다시 시도해주세요."
            }
            yield f"data: {json.dumps(error_event, ensure_ascii=False)}\n\n"
        except Exception as e:
            logger.error(f"스트리밍 중 에러 발생: {e}", exc_info=True)
            error_event = {
                "type": "error",
                "delta": f"에러 발생: {str(e)}"
            }
            yield f"data: {json.dumps(error_event, ensure_ascii=False)}\n\n"
    
    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",  # Nginx 버퍼링 비활성화
            "X-Accept-Timeout": "300"  # 타임아웃 힌트 (초)
        }
    )


async def save_thought_history(chapter_title: str, thoughts: list, answer: str):
    """
    추론 과정 히스토리를 저장합니다.
    현재는 메모리 기반 저장소를 사용하며, 나중에 DB(Firebase 등)로 확장 가능합니다.
    """
    import os
    from datetime import datetime
    from pathlib import Path
    
    # 임시 저장: 파일 시스템에 JSON으로 저장 (나중에 DB로 교체)
    history_dir = Path("thought_history")
    history_dir.mkdir(exist_ok=True)
    
    history_data = {
        "chapter_title": chapter_title,
        "timestamp": datetime.utcnow().isoformat(),
        "thoughts": thoughts,
        "final_answer": answer
    }
    
    # 파일명: chapter_title_timestamp.json
    safe_title = "".join(c for c in chapter_title if c.isalnum() or c in (' ', '-', '_')).rstrip()
    filename = f"{safe_title}_{datetime.utcnow().strftime('%Y%m%d_%H%M%S')}.json"
    filepath = history_dir / filename
    
    with open(filepath, "w", encoding="utf-8") as f:
        json.dump(history_data, f, ensure_ascii=False, indent=2)
    
    # TODO: Firebase 또는 다른 DB에 저장하는 로직으로 교체
    # 예시:
    # firebase_db.collection('thought_history').add(history_data)


@router.get("/history/{chapter_title}")
async def get_thought_history(chapter_title: str):
    """
    특정 챕터의 추론 과정 히스토리를 조회합니다.
    사용자가 나중에 다시 들어왔을 때 스트리밍했던 추론 과정을 다시 볼 수 있습니다.
    """
    from pathlib import Path
    
    history_dir = Path("thought_history")
    if not history_dir.exists():
        return {"history": [], "message": "히스토리가 없습니다."}
    
    # 챕터 제목으로 파일 검색 (안전한 파일명 변환)
    safe_title = "".join(c for c in chapter_title if c.isalnum() or c in (' ', '-', '_')).rstrip()
    pattern = f"{safe_title}_*.json"
    files = list(history_dir.glob(pattern))
    
    if not files:
        return {"history": [], "message": "해당 챕터의 히스토리를 찾을 수 없습니다."}
    
    # 가장 최근 파일 반환
    latest_file = max(files, key=lambda p: p.stat().st_mtime)
    
    try:
        with open(latest_file, "r", encoding="utf-8") as f:
            history_data = json.load(f)
        return history_data
    except Exception as e:
        return {"error": f"히스토리 로드 실패: {str(e)}"}

