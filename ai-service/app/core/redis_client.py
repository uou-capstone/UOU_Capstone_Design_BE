# ai-service/app/core/redis_client.py
"""
Redis 클라이언트 모듈
앱 전체에서 재사용할 수 있도록 Redis 연결을 관리하는 싱글톤 모듈
ConnectionPool을 사용하여 연결 효율성 향상
"""
import os
import redis.asyncio as redis
from typing import AsyncGenerator, Iterator
from dotenv import load_dotenv

load_dotenv()


class RedisClient:
    """Redis 연결 관리 클래스 (싱글톤 패턴, ConnectionPool 사용)"""
    
    def __init__(self):
        # 도커 환경에서는 'redis', 로컬 테스트 시 'localhost'
        self.redis_host = os.getenv("REDIS_HOST", "localhost")
        self.redis_port = int(os.getenv("REDIS_PORT", 6379))
        self.pool = None

    def get_client(self) -> redis.Redis:
        """
        Redis 클라이언트 인스턴스를 반환합니다.
        ConnectionPool을 사용하여 연결을 효율적으로 관리합니다.
        
        Returns:
            redis.Redis: Redis 비동기 클라이언트
        """
        if self.pool is None:
            self.pool = redis.ConnectionPool(
                host=self.redis_host,
                port=self.redis_port,
                db=0,
                decode_responses=True,  # 문자열 자동 디코딩
                encoding="utf-8",
                socket_timeout=5.0,
                socket_connect_timeout=5.0,
                max_connections=50  # 최대 연결 수
            )
        return redis.Redis(connection_pool=self.pool)

    async def close(self):
        """Redis 연결 풀을 종료합니다."""
        if self.pool:
            await self.pool.disconnect()
            self.pool = None

    async def health_check(self) -> bool:
        """
        Redis 연결 상태를 확인합니다.
        
        Returns:
            bool: 연결 성공 여부
        """
        try:
            client = self.get_client()
            await client.ping()
            return True
        except Exception as e:
            print(f"[Redis] Health check failed: {e}")
            return False


# 전역 인스턴스
redis_manager = RedisClient()


# FastAPI 의존성 주입용 함수 (Generator 패턴)
async def get_redis():
    """
    FastAPI의 Depends에서 사용할 수 있는 Redis 클라이언트 반환 함수
    Generator 패턴으로 사용 후 자동 정리
    
    Yields:
        redis.Redis: Redis 비동기 클라이언트
    """
    client = redis_manager.get_client()
    try:
        yield client
    finally:
        # ConnectionPool을 사용하므로 개별 클라이언트는 닫지 않음
        # 풀 자체는 애플리케이션 종료 시 close()로 정리
        pass
