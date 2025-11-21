from fastapi import FastAPI, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError
from app.routers.delegator import router as delegator_router
from app.routers.pdf import router as pdf_router
from app.routers.lecture import router as lecture_router
from app.routers.qa import router as qa_router
from app.routers.upload import router as upload_router

def create_app() -> FastAPI:
    app = FastAPI(title="AI Service")
    
    # Pydantic 검증 에러 핸들링
    @app.exception_handler(RequestValidationError)
    async def validation_exception_handler(request: Request, exc: RequestValidationError):
        # 요청 본문 읽기 (한 번만)
        try:
            body = await request.body()
            body_str = body.decode('utf-8') if body else "N/A"
        except Exception as e:
            body_str = f"요청 본문 읽기 실패: {str(e)}"
        
        print(f"[ERROR] ========== 요청 검증 실패 ==========")
        print(f"[ERROR] URL: {request.url}")
        print(f"[ERROR] Method: {request.method}")
        print(f"[ERROR] 에러 상세: {exc.errors()}")
        print(f"[ERROR] 요청 본문: {body_str}")
        print(f"[ERROR] =====================================")
        
        return JSONResponse(
            status_code=status.HTTP_400_BAD_REQUEST,
            content={
                "detail": exc.errors(),
                "body": body_str,
                "message": "요청 형식이 올바르지 않습니다. JSON 형식으로 stage와 payload를 전달해야 합니다."
            },
        )
    
    # CORS 설정 (Spring Boot 연동을 위해)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],  # 프로덕션에서는 특정 도메인으로 제한 권장
        allow_credentials=True,
        allow_methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"],  # GET, POST 등 명시적으로 허용
        allow_headers=["*"],
        expose_headers=["*"],
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

