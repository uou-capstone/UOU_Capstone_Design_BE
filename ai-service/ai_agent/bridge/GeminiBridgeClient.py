"""
GeminiBridgeClient

설계서 §9 기준: thought/answer 채널 분리, NDJSON 포맷 통일.
모든 서브 에이전트는 이 클라이언트를 통해 Gemini API를 호출한다.

스트리밍 출력 포맷 (NDJSON — agent_delta 규격):
  {"type": "agent_delta", "agent": "explainer", "tool": "EXPLAIN_PAGE", "channel": "thought", "delta": "..."}
  {"type": "agent_delta", "agent": "explainer", "tool": "EXPLAIN_PAGE", "channel": "main", "delta": "..."}
  {"type": "done", "agent": "explainer", "tool": "EXPLAIN_PAGE", "final": true, "data": {...}}
  {"type": "error", "agent": "explainer", "message": "..."}
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import pathlib
import threading
import time
from collections import OrderedDict
from functools import lru_cache
from typing import Any, AsyncGenerator, Dict, List, Optional, Type

from dotenv import load_dotenv
from google import genai
from google.genai import types
from pydantic import BaseModel

from ai_agent.types.domain import NdjsonEvent, NdjsonEventType

load_dotenv()

logger = logging.getLogger(__name__)

_STREAM_TIMEOUT = float(os.getenv("GEMINI_STREAM_TIMEOUT", "300"))
_HEARTBEAT_INTERVAL = float(os.getenv("GEMINI_HEARTBEAT_INTERVAL", "10"))

# ---------------------------------------------------------------------------
# PDF 하이브리드 캐시 (모듈 레벨)
# ---------------------------------------------------------------------------

#: 이 크기 미만은 인라인 전송(Part.from_bytes), 이상은 File API 업로드
_PDF_INLINE_THRESHOLD: int = (
    int(os.getenv("PDF_INLINE_MB", "15")) * 1024 * 1024
)

#: File API URI 유효기간 48 h → 1 h 여유를 두고 47 h 에 갱신
_FILE_API_TTL: int = 47 * 3600


@lru_cache(maxsize=32)
def _load_bytes_cached(pdf_path: str, _mtime_ns: int) -> types.Part:
    """
    소형 PDF: 바이트 인라인 LRU 캐시 (동기).
    _mtime_ns 를 캐시 키에 포함해 파일이 교체되면 자동으로 새 캐시 엔트리를 생성한다.
    """
    data = pathlib.Path(pdf_path).read_bytes()
    return types.Part.from_bytes(data=data, mime_type="application/pdf")


# Large PDF in-memory cache (Redis fallback).
# Bounded by _MEM_CACHE_MAX to prevent unbounded growth.
# { pdf_path: {"uri": str, "mtime": float, "t": float} }  (insertion-ordered for LRU eviction)
_MEM_CACHE_MAX = 64
_file_api_mem_cache: OrderedDict[str, Dict[str, Any]] = OrderedDict()

# Per-file upload lock — ensures only one upload per file at a time.
# Bounded by _LOCK_CACHE_MAX; stale (unlocked) entries are evicted on overflow.
_LOCK_CACHE_MAX = 128
_upload_locks: OrderedDict[str, asyncio.Lock] = OrderedDict()


def _get_upload_lock(pdf_path: str) -> asyncio.Lock:
    if pdf_path in _upload_locks:
        _upload_locks.move_to_end(pdf_path)
        return _upload_locks[pdf_path]
    # Evict oldest unlocked entry if at capacity
    if len(_upload_locks) >= _LOCK_CACHE_MAX:
        for old_path, old_lock in list(_upload_locks.items()):
            if not old_lock.locked():
                del _upload_locks[old_path]
                break
    lock = asyncio.Lock()
    _upload_locks[pdf_path] = lock
    return lock


def _set_mem_cache(pdf_path: str, entry: Dict[str, Any]) -> None:
    """Insert/update memory cache with LRU eviction."""
    if pdf_path in _file_api_mem_cache:
        _file_api_mem_cache.move_to_end(pdf_path)
    _file_api_mem_cache[pdf_path] = entry
    while len(_file_api_mem_cache) > _MEM_CACHE_MAX:
        _file_api_mem_cache.popitem(last=False)


def _redis_key(pdf_path: str) -> str:
    """Redis 키: fa:file_api:{md5(path)[:16]}"""
    h = hashlib.md5(pdf_path.encode()).hexdigest()[:16]
    return f"fa:file_api:{h}"


def _redis_file_ref_key(pdf_path: str, fingerprint: str | None = None) -> str:
    """Redis key for a fingerprint-aware Gemini Files API reference."""
    raw = fingerprint or pdf_path
    h = hashlib.sha256(raw.encode()).hexdigest()[:16]
    return f"fa:file_ref:{h}"


class GeminiBridgeClient:
    """
    Gemini API 호출을 담당하는 중앙 브리지 클라이언트.

    - 비스트리밍: `generate(...)` → str
    - 구조화 출력: `generate_structured(...)` → str (JSON)
    - 스트리밍: `stream(...)` → AsyncGenerator[NdjsonEvent, None]
    """

    DEFAULT_MODEL = "gemini-2.5-flash"

    def __init__(self, api_key: Optional[str] = None, model: Optional[str] = None):
        self._api_key = api_key or os.getenv("GEMINI_API_KEY", "")
        self._model = model or self.DEFAULT_MODEL
        self._client = genai.Client(api_key=self._api_key)

    def _get_redis(self):
        """
        Returns the shared app-level Redis client (connection pool).
        Falls back to None on import error so the module can be used standalone.
        """
        try:
            from app.core.redis_client import redis_manager
            return redis_manager.get_client()
        except Exception as exc:
            logger.warning("[FileAPI] Redis client unavailable, using memory cache only: %s", exc)
            return None

    # ------------------------------------------------------------------
    # 비스트리밍 (단순 텍스트 생성)
    # ------------------------------------------------------------------

    async def generate(
        self,
        contents: List[Any],
        config: Optional[types.GenerateContentConfig] = None,
    ) -> str:
        """비동기 텍스트 생성. 동기 SDK를 asyncio.to_thread로 감쌈."""
        response = await asyncio.wait_for(
            asyncio.to_thread(
                self._client.models.generate_content,
                model=self._model,
                contents=contents,
                config=config,
            ),
            timeout=_STREAM_TIMEOUT,
        )
        return response.text or ""

    # ------------------------------------------------------------------
    # 구조화 출력 (Pydantic 스키마 기반 JSON)
    # ------------------------------------------------------------------

    async def generate_structured(
        self,
        contents: List[Any],
        response_schema: Type[BaseModel],
    ) -> str:
        """Structured Output(JSON) 모드 호출. 응답 JSON 문자열 반환."""
        config = types.GenerateContentConfig(
            response_mime_type="application/json",
            response_schema=response_schema,
        )
        response = await asyncio.wait_for(
            asyncio.to_thread(
                self._client.models.generate_content,
                model=self._model,
                contents=contents,
                config=config,
            ),
            timeout=_STREAM_TIMEOUT,
        )
        return response.text or ""

    # ------------------------------------------------------------------
    # 스트리밍 (thought_delta / answer_delta / done)
    # ------------------------------------------------------------------

    async def stream(
        self,
        contents: List[Any],
        config: Optional[types.GenerateContentConfig] = None,
        done_data: Optional[Dict[str, Any]] = None,
        agent: str = "system",
        tool: Optional[str] = None,
    ) -> AsyncGenerator[NdjsonEvent, None]:
        """
        Gemini 스트리밍 호출. thought/main 채널을 분리해 agent_delta 포맷으로 yield한다.

        Args:
            contents: Gemini API에 전달할 컨텐츠 리스트
            done_data: 완료 이벤트에 포함할 추가 데이터
            agent: 호출 주체 에이전트 이름 ("explainer"|"qa"|"quiz"|"grader"|"system")
            tool: 호출 툴 이름 (ToolName 문자열)

        [핵심] 동기 SDK 이터레이터의 각 next() 호출은 네트워크 I/O를 블로킹한다.
        asyncio.Queue + run_in_executor 패턴으로 이벤트 루프 블로킹을 방지한다.
        """
        loop = asyncio.get_running_loop()
        # maxsize prevents unbounded growth when the consumer disconnects mid-stream.
        queue: asyncio.Queue[Optional[NdjsonEvent]] = asyncio.Queue(maxsize=200)
        _SENTINEL = None
        # Cancellation flag: set when the consumer exits (or queue is full) so
        # the producer thread stops early.
        _cancelled = threading.Event()

        def _safe_put(evt: Optional[NdjsonEvent]) -> None:
            """
            Called inside the event loop via call_soon_threadsafe.
            If the queue is full the consumer is gone — signal the producer to stop.
            """
            try:
                queue.put_nowait(evt)
            except asyncio.QueueFull:
                _cancelled.set()

        def _iterate_in_thread() -> None:
            """Consumes the sync SDK iterator in a thread pool and feeds the async queue."""
            try:
                response_iter = self._client.models.generate_content_stream(
                    model=self._model,
                    contents=contents,
                    config=config,
                )
                for chunk in response_iter:
                    if _cancelled.is_set():
                        break
                    if not chunk.candidates:
                        continue
                    for candidate in chunk.candidates:
                        if not candidate.content or not candidate.content.parts:
                            continue
                        for part in candidate.content.parts:
                            if _cancelled.is_set():
                                break
                            text = getattr(part, "text", None)
                            if not text:
                                continue
                            is_thought = getattr(part, "thought", False)
                            event = NdjsonEvent(
                                type=NdjsonEventType.AGENT_DELTA,
                                agent=agent,
                                tool=tool,
                                channel="thought" if is_thought else "main",
                                delta=text,
                            )
                            loop.call_soon_threadsafe(_safe_put, event)
            except Exception as exc:
                error_event = NdjsonEvent(
                    type=NdjsonEventType.ERROR,
                    agent=agent,
                    message=f"GeminiBridgeClient 스트리밍 실패: {exc}",
                )
                try:
                    loop.call_soon_threadsafe(_safe_put, error_event)
                except Exception:
                    pass
            finally:
                try:
                    loop.call_soon_threadsafe(_safe_put, _SENTINEL)
                except Exception:
                    pass

        future = loop.run_in_executor(None, _iterate_in_thread)

        _heartbeat = NdjsonEvent(type=NdjsonEventType.HEARTBEAT)
        total_waited = 0.0
        timed_out = False
        try:
            while True:
                try:
                    event = await asyncio.wait_for(
                        queue.get(), timeout=_HEARTBEAT_INTERVAL
                    )
                    if event is _SENTINEL:
                        break
                    yield event
                    total_waited = 0.0
                except asyncio.TimeoutError:
                    total_waited += _HEARTBEAT_INTERVAL
                    if total_waited >= _STREAM_TIMEOUT:
                        timed_out = True
                        yield NdjsonEvent(
                            type=NdjsonEventType.ERROR,
                            agent=agent,
                            message=f"Gemini 스트리밍 타임아웃 ({_STREAM_TIMEOUT}초)",
                        )
                        break
                    yield _heartbeat
        finally:
            _cancelled.set()  # Signal the producer thread to stop
            await future

        if not timed_out:
            yield NdjsonEvent(
                type=NdjsonEventType.DONE,
                agent=agent,
                tool=tool,
                final=True,
                data=done_data or {},
            )

    # ------------------------------------------------------------------
    # 파일 로더 (공통 유틸) — 하이브리드 PDF 로딩
    # ------------------------------------------------------------------

    async def load_pdf_part(self, pdf_path: str) -> types.Part:
        """
        PDF 크기에 따라 전달 방식을 자동 선택한다.

        - 15 MB 미만 : Part.from_bytes() + LRU 캐시 (즉시 반환, 네트워크 없음)
        - 15 MB 이상 : File API 업로드 + URI 캐시
            - Redis(L1) → 메모리(L2) 순서로 조회
            - asyncio.Lock 으로 동시 업로드 방지 (double-checked locking)
            - mtime 변경 감지 → 자동 재업로드
            - 47 h TTL → 만료 전 자동 갱신
            - 업로드 실패 시 Part.from_bytes() 폴백

        Raises:
            FileNotFoundError: PDF 파일이 존재하지 않을 때
        """
        p = pathlib.Path(pdf_path)
        if not p.exists():
            raise FileNotFoundError(f"PDF 파일 없음: {pdf_path}")

        stat = p.stat()
        if stat.st_size < _PDF_INLINE_THRESHOLD:
            return _load_bytes_cached(pdf_path, stat.st_mtime_ns)

        return await self._load_via_file_api(pdf_path)

    async def load_pdf_file_ref_part(
        self,
        pdf_path: str,
        *,
        fingerprint: str | None = None,
    ) -> tuple[types.Part, Dict[str, Any]]:
        """
        QA 보조 근거용으로 Gemini Files API URI part를 우선 생성한다.

        `load_pdf_part()`는 작은 PDF를 인라인 바이트로 보내지만, 페이지 텍스트
        추출이 약한 QA에서는 레퍼런스처럼 원본 PDF fileRef를 같이 넘기는 편이
        유리하다. 업로드 실패 시 소형 PDF는 inline Part.from_bytes로 폴백할 수
        있고, 대용량 PDF는 inline fallback 없이 예외를 올려 상위에서 기존 page
        text/index QA로 재시도하게 한다.
        """
        p = pathlib.Path(pdf_path)
        if not p.exists():
            raise FileNotFoundError(f"PDF 파일 없음: {pdf_path}")

        stat = p.stat()
        mtime = stat.st_mtime
        cache_id = f"{pdf_path}:{fingerprint or stat.st_mtime_ns}"
        rkey = _redis_file_ref_key(pdf_path, fingerprint)
        lock = _get_upload_lock(cache_id)

        async with lock:
            uri = await self._get_cached_uri(rkey, cache_id, mtime)
            if uri:
                metadata = {
                    "fileName": p.name,
                    "fileUri": uri,
                    "mimeType": "application/pdf",
                    "source": "FILE_API_CACHE",
                    "fingerprint": fingerprint,
                }
                return types.Part.from_uri(file_uri=uri, mime_type="application/pdf"), metadata

            size_kb = stat.st_size // 1024
            logger.info("[FileAPI] QA fileRef 업로드 시작: %s (%d KB)", pdf_path, size_kb)
            try:
                uploaded = await asyncio.to_thread(
                    self._client.files.upload, path=pdf_path
                )
                uri = uploaded.uri
                await self._set_cached_uri(rkey, cache_id, uri, mtime)
                metadata = {
                    "fileName": getattr(uploaded, "name", None) or p.name,
                    "fileUri": uri,
                    "mimeType": getattr(uploaded, "mime_type", None) or "application/pdf",
                    "source": "FILE_API",
                    "fingerprint": fingerprint,
                    "uploadedAt": time.time(),
                }
                logger.info("[FileAPI] QA fileRef 업로드 완료: %s", uri)
                return types.Part.from_uri(
                    file_uri=uri,
                    mime_type=metadata["mimeType"],
                ), metadata
            except Exception as exc:
                if stat.st_size >= _PDF_INLINE_THRESHOLD:
                    logger.warning(
                        "[FileAPI] QA fileRef 업로드 실패, 대용량 PDF inline fallback 생략: %s (%s)",
                        pdf_path, exc,
                    )
                    raise RuntimeError("FILE_API_UPLOAD_FAILED") from exc
                logger.warning(
                    "[FileAPI] QA fileRef 업로드 실패 → 소형 PDF Part.from_bytes 폴백: %s (%s)",
                    pdf_path, exc,
                )
                data = await asyncio.to_thread(p.read_bytes)
                metadata = {
                    "fileName": p.name,
                    "fileUri": None,
                    "mimeType": "application/pdf",
                    "source": "INLINE_FALLBACK",
                    "fingerprint": fingerprint,
                    "fallbackReason": "FILE_API_UPLOAD_FAILED",
                }
                return types.Part.from_bytes(data=data, mime_type="application/pdf"), metadata

    async def invalidate_pdf_file_ref_cache(
        self,
        pdf_path: str,
        *,
        fingerprint: str | None = None,
    ) -> None:
        """Remove cached Gemini Files API URI for a PDF/fingerprint pair."""
        try:
            p = pathlib.Path(pdf_path)
            stat = p.stat()
            cache_id = f"{pdf_path}:{fingerprint or stat.st_mtime_ns}"
        except Exception:
            cache_id = f"{pdf_path}:{fingerprint or ''}"
        rkey = _redis_file_ref_key(pdf_path, fingerprint)

        r = self._get_redis()
        if r:
            try:
                await r.delete(rkey)
            except Exception as exc:
                logger.warning("[FileAPI] Redis fileRef cache 삭제 오류: %s", exc)

        _file_api_mem_cache.pop(cache_id, None)

    async def _load_via_file_api(self, pdf_path: str) -> types.Part:
        mtime = pathlib.Path(pdf_path).stat().st_mtime
        rkey = _redis_key(pdf_path)
        lock = _get_upload_lock(pdf_path)

        async with lock:
            # Lock 획득 후 재확인 — 다른 코루틴이 이미 업로드했을 수 있음
            uri = await self._get_cached_uri(rkey, pdf_path, mtime)
            if uri:
                logger.debug("[FileAPI] 캐시 적중: %s", pdf_path)
                return types.Part.from_uri(file_uri=uri, mime_type="application/pdf")

            size_kb = pathlib.Path(pdf_path).stat().st_size // 1024
            logger.info("[FileAPI] 업로드 시작: %s (%d KB)", pdf_path, size_kb)
            try:
                uploaded = await asyncio.to_thread(
                    self._client.files.upload, path=pdf_path
                )
                uri = uploaded.uri
                await self._set_cached_uri(rkey, pdf_path, uri, mtime)
                logger.info("[FileAPI] 업로드 완료: %s", uri)
                return types.Part.from_uri(file_uri=uri, mime_type="application/pdf")
            except Exception as exc:
                logger.warning(
                    "[FileAPI] 업로드 실패 → Part.from_bytes 폴백: %s (%s)",
                    pdf_path, exc,
                )
                data = pathlib.Path(pdf_path).read_bytes()
                return types.Part.from_bytes(data=data, mime_type="application/pdf")

    async def _get_cached_uri(
        self, rkey: str, pdf_path: str, mtime: float
    ) -> Optional[str]:
        """Redis → 메모리 순으로 유효한 URI 조회. 없으면 None."""
        now = time.time()

        # L1: Redis
        r = self._get_redis()
        if r:
            try:
                raw = await r.get(rkey)
                if raw:
                    entry = json.loads(raw)
                    if entry.get("mtime") == mtime:
                        return entry["uri"]
            except Exception as exc:
                logger.warning("[FileAPI] Redis 조회 오류: %s", exc)

        # L2: 메모리
        entry = _file_api_mem_cache.get(pdf_path)
        if (
            entry
            and entry["mtime"] == mtime
            and (now - entry["t"]) < _FILE_API_TTL
        ):
            return entry["uri"]

        return None

    async def _set_cached_uri(
        self, rkey: str, pdf_path: str, uri: str, mtime: float
    ) -> None:
        """Redis + 메모리 캐시에 URI 저장 (TTL 47 h)."""
        now = time.time()
        payload = json.dumps({"uri": uri, "mtime": mtime, "t": now})

        r = self._get_redis()
        if r:
            try:
                await r.set(rkey, payload, ex=_FILE_API_TTL)
            except Exception as exc:
                logger.warning("[FileAPI] Redis 저장 오류: %s", exc)

        _set_mem_cache(pdf_path, {"uri": uri, "mtime": mtime, "t": now})

    @staticmethod
    def load_text(text_path: str) -> str:
        """텍스트 파일(MD, TXT) 읽기"""
        path = pathlib.Path(text_path)
        for enc in ("utf-8", "utf-8-sig", "cp949", "latin-1"):
            try:
                return path.read_text(encoding=enc)
            except UnicodeDecodeError:
                continue
        return path.read_text(encoding="utf-8", errors="replace")
