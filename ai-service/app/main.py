import hmac
import os
from typing import Callable

from fastapi import FastAPI, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError
from app.routers.session import router as session_router
from app.routers.bridge import router as bridge_router
from app.routers.report import router as report_router
from app.routers.exam import router as exam_router
from app.routers.bridge_agents import router as bridge_agents_router
from app.routers.pdf import router as pdf_router
from app.routers.upload import router as upload_router
from app.routers.note_gen import router as note_gen_router
from app.routers.test_gen import router as test_gen_router
from app.routers.lecture import router as lecture_router   # [v2] Classic Track
from app.routers.qa import router as qa_router             # [v2] Classic Track
from app.core.redis_client import redis_manager


_AUTH_EXEMPT_PATHS = {
    "/health",
    "/docs",
    "/redoc",
    "/openapi.json",
}
_PLACEHOLDER_SECRETS = {
    "",
    "your_secret_key",
    "your_ai_secret_key",
    "change_me",
    "FUCKING_AWSOME_KEY",
}


def _configured_secret() -> str | None:
    secret = os.getenv("AI_SECRET_KEY", "").strip()
    return None if secret in _PLACEHOLDER_SECRETS else secret


def _cors_origins() -> list[str]:
    raw = os.getenv(
        "CORS_ALLOWED_ORIGINS",
        "http://localhost:8080,http://127.0.0.1:8080,http://localhost:3000,http://127.0.0.1:3000,http://localhost:5173,http://127.0.0.1:5173",
    )
    origins = [origin.strip() for origin in raw.split(",") if origin.strip()]
    return origins or ["http://localhost:8080", "http://127.0.0.1:8080"]


def _cors_allow_credentials(origins: list[str]) -> bool:
    if "*" in origins:
        return False
    return os.getenv("CORS_ALLOW_CREDENTIALS", "false").lower() == "true"


def create_app() -> FastAPI:
    app = FastAPI(title="AI Service")

    @app.middleware("http")
    async def ai_secret_middleware(request: Request, call_next: Callable):
        path = request.url.path
        if request.method == "OPTIONS" or path in _AUTH_EXEMPT_PATHS:
            return await call_next(request)

        secret = _configured_secret()
        if not secret:
            return await call_next(request)

        supplied = request.headers.get("X-AI-SECRET-KEY", "")
        if not hmac.compare_digest(supplied, secret):
            return JSONResponse(
                status_code=status.HTTP_401_UNAUTHORIZED,
                content={"detail": "Unauthorized AI service request."},
            )

        return await call_next(request)
    
    # Pydantic 검증 에러 핸들링
    @app.exception_handler(RequestValidationError)
    async def validation_exception_handler(request: Request, exc: RequestValidationError):
        # 요청 본문 읽기 (한 번만)
        try:
            body = await request.body()
            body_str = body.decode('utf-8') if body else "N/A"
        except Exception:
            body_str = "요청 본문 읽기 실패"
        
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
                "message": "요청 형식이 올바르지 않습니다."
            },
        )
    
    # CORS 설정 (Spring Boot 연동을 위해)
    cors_origins = _cors_origins()
    app.add_middleware(
        CORSMiddleware,
        allow_origins=cors_origins,
        allow_credentials=_cors_allow_credentials(cors_origins),
        allow_methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"],  # GET, POST 등 명시적으로 허용
        allow_headers=["*"],
        expose_headers=["*"],
    )
    
    # [v3] Integrated Track — 통합 에이전트 (세션 기반 오케스트레이션)
    app.include_router(session_router)
    app.include_router(bridge_router)
    app.include_router(report_router)
    app.include_router(exam_router)
    app.include_router(bridge_agents_router)

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
        except Exception:
            redis_status = "error"
        
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
