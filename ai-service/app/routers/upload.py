import shutil
import uuid
from fastapi import APIRouter, UploadFile, File, HTTPException, Query
from fastapi.responses import FileResponse
from pathlib import Path


router = APIRouter(prefix="/api/files", tags=["files"])

_UPLOADS_DIR = Path("uploads").resolve()
_ALLOWED_SUFFIXES = {".pdf", ".md", ".txt"}


@router.post("/upload")
async def upload_file(file: UploadFile = File(...)):
    try:
        _UPLOADS_DIR.mkdir(parents=True, exist_ok=True)

        original_suffix = Path(file.filename or "").suffix.lower()
        if original_suffix not in _ALLOWED_SUFFIXES:
            raise HTTPException(
                status_code=400,
                detail=f"허용되지 않는 파일 형식입니다. 허용: {', '.join(_ALLOWED_SUFFIXES)}",
            )

        safe_name = f"{uuid.uuid4()}{original_suffix}"
        dest_path = _UPLOADS_DIR / safe_name

        with dest_path.open("wb") as buffer:
            shutil.copyfileobj(file.file, buffer)

        return {"filename": file.filename, "path": str(dest_path)}
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/serve")
async def serve_file(path: str = Query(..., description="요청할 파일의 절대 또는 상대 경로")):
    """
    업로드된 파일을 서빙하는 엔드포인트.
    - 쿼리 파라미터로 전달된 경로가 uploads 디렉터리 내부인지 검증
    - 존재하는 파일만 FileResponse로 반환
    """
    try:
        file_path = Path(path).resolve()
        uploads_dir = Path("uploads").resolve()

        # Path Traversal 방지: uploads 디렉터리 내부 경로만 허용
        try:
            file_path.relative_to(uploads_dir)
        except ValueError:
            raise HTTPException(status_code=403, detail="접근이 허용되지 않은 경로입니다.")

        if not file_path.exists() or not file_path.is_file():
            raise HTTPException(status_code=404, detail="요청한 파일을 찾을 수 없습니다.")

        return FileResponse(path=str(file_path), filename=file_path.name)

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

