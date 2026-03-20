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
import functools
import os
import pathlib
from functools import lru_cache
from typing import Any, AsyncGenerator, Dict, List, Optional, Type

from dotenv import load_dotenv
from google import genai
from google.genai import types
from pydantic import BaseModel

from ai_agent.types.domain import NdjsonEvent, NdjsonEventType

load_dotenv()

_STREAM_TIMEOUT = float(os.getenv("GEMINI_STREAM_TIMEOUT", "300"))
_HEARTBEAT_INTERVAL = float(os.getenv("GEMINI_HEARTBEAT_INTERVAL", "10"))


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
        loop = asyncio.get_event_loop()
        queue: asyncio.Queue[Optional[NdjsonEvent]] = asyncio.Queue()
        _SENTINEL = None

        def _iterate_in_thread() -> None:
            """별도 스레드에서 sync 이터레이터를 소비해 큐에 넣는다."""
            try:
                response_iter = self._client.models.generate_content_stream(
                    model=self._model,
                    contents=contents,
                )
                for chunk in response_iter:
                    if not chunk.candidates:
                        continue
                    for candidate in chunk.candidates:
                        if not candidate.content or not candidate.content.parts:
                            continue
                        for part in candidate.content.parts:
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
                            loop.call_soon_threadsafe(queue.put_nowait, event)
            except Exception as exc:
                error_event = NdjsonEvent(
                    type=NdjsonEventType.ERROR,
                    agent=agent,
                    message=f"GeminiBridgeClient 스트리밍 실패: {exc}",
                )
                loop.call_soon_threadsafe(queue.put_nowait, error_event)
            finally:
                loop.call_soon_threadsafe(queue.put_nowait, _SENTINEL)

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
                    # 연결 유지 heartbeat (10초마다)
                    yield _heartbeat
        finally:
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
    # 파일 로더 (공통 유틸) - PDF는 LRU 캐시로 중복 읽기 방지
    # ------------------------------------------------------------------

    @staticmethod
    @lru_cache(maxsize=32)
    def load_pdf_part(pdf_path: str) -> types.Part:
        """PDF 파일을 Gemini Part로 변환. 동일 경로는 캐시에서 반환."""
        data = pathlib.Path(pdf_path).read_bytes()
        return types.Part.from_bytes(data=data, mime_type="application/pdf")

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
