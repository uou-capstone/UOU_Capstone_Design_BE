from fastapi import APIRouter, UploadFile, File, HTTPException
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

        return {"filename": file.filename, "path": str(dest_path.resolve())}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


