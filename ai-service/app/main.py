from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from app.routers.delegator import router as delegator_router
from app.routers.pdf import router as pdf_router
from app.routers.lecture import router as lecture_router
from app.routers.qa import router as qa_router
from app.routers.upload import router as upload_router

def create_app() -> FastAPI:
    app = FastAPI(title="AI Service")
    
    # CORS 설정 (Spring Boot 연동을 위해)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],  # 프로덕션에서는 특정 도메인으로 제한 권장
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    
    app.include_router(delegator_router)
    app.include_router(pdf_router)
    app.include_router(lecture_router)
    app.include_router(qa_router)
    app.include_router(upload_router)
    
    @app.get("/health")
    def health():
        return {"status": "ok"}
    return app


app = create_app()

