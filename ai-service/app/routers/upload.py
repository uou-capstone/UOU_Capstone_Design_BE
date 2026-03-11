from fastapi import APIRouter, UploadFile, File, HTTPException, Query
from fastapi.responses import FileResponse
from pathlib import Path
import shutil


router = APIRouter(prefix="/api/files", tags=["files"])


@router.post("/upload")
async def upload_file(file: UploadFile = File(...)):
    try:
        uploads_dir = Path("uploads")
        uploads_dir.mkdir(parents=True, exist_ok=True)

        dest_path = uploads_dir / file.filename
        with dest_path.open("wb") as buffer:
            shutil.copyfileobj(file.file, buffer)

        # 절대 경로로 반환
        return {"filename": file.filename, "path": str(dest_path.resolve())}
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

