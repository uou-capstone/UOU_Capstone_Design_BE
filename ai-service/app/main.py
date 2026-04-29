from fastapi import FastAPI, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError
from app.routers.session import router as session_router
from app.routers.bridge import router as bridge_router
from app.routers.pdf import router as pdf_router
from app.routers.upload import router as upload_router
from app.routers.note_gen import router as note_gen_router
from app.routers.test_gen import router as test_gen_router
from app.routers.lecture import router as lecture_router   # [v2] Classic Track
from app.routers.qa import router as qa_router             # [v2] Classic Track
from app.core.redis_client import redis_manager

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
                "message": "요청 형식이 올바르지 않습니다."
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
    
    # [v3] Integrated Track — 통합 에이전트 (세션 기반 오케스트레이션)
    app.include_router(session_router)
    app.include_router(bridge_router)

    # [v2] Classic Track — 개별 에이전트 직접 호출
    app.include_router(lecture_router)
    app.include_router(qa_router)
    app.include_router(note_gen_router)
    app.include_router(test_gen_router)

    # 공용 유틸
    app.include_router(pdf_router)
    app.include_router(upload_router)
    
    @app.get("/health")
    async def health():
        """헬스 체크 엔드포인트 (Redis 상태 포함)"""
        redis_status = "unknown"
        try:
            redis_healthy = await redis_manager.health_check()
            redis_status = "connected" if redis_healthy else "disconnected"
        except Exception as e:
            redis_status = f"error: {str(e)}"
        
        return {
            "status": "ok",
            "redis": redis_status
        }
    
    @app.on_event("shutdown")
    async def shutdown_event():
        """애플리케이션 종료 시 Redis 연결 종료"""
        await redis_manager.close()
        print("[Redis] Connection closed")
    
    return app


app = create_app()

