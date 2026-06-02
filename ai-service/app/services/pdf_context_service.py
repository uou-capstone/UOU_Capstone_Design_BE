from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re
from typing import Any

from pypdf import PdfReader


@dataclass(frozen=True)
class PageContext:
    page: int
    page_text: str
    prev_text: str = ""
    next_text: str = ""
    page_count: int = 0

    @property
    def has_page_text(self) -> bool:
        return bool(self.page_text.strip())


@dataclass(frozen=True)
class RelevantPage:
    page: int
    text: str
    score: float
    matched_terms: tuple[str, ...] = ()


class PdfContextService:
    """
    Lightweight page text index for v3 learning agents.

    The reference MergeEduAgentFull flow sends current/prev/next page text to
    the explainer instead of asking Gemini to infer the page from the full PDF.
    This service keeps that behavior local and falls back gracefully when text
    extraction fails.
    """

    def __init__(self) -> None:
        self._cache: dict[str, dict[str, Any]] = {}

    def read_page_context(self, pdf_path: str | None, page: int) -> PageContext | None:
        if not pdf_path:
            return None

        path = Path(pdf_path)
        if not path.exists():
            return None

        try:
            pages = self._load_pages(path)
        except Exception:
            return None

        if not pages:
            return None

        target = max(1, int(page or 1))
        target = min(target, len(pages))
        index = target - 1

        return PageContext(
            page=target,
            page_text=pages[index],
            prev_text=pages[index - 1] if index > 0 else "",
            next_text=pages[index + 1] if index + 1 < len(pages) else "",
            page_count=len(pages),
        )

    def read_all_pages(self, pdf_path: str | None) -> list[str]:
        if not pdf_path:
            return []

        path = Path(pdf_path)
        if not path.exists():
            return []

        try:
            return self._load_pages(path)
        except Exception:
            return []

    def search_relevant_pages(
        self,
        pdf_path: str | None,
        query: str,
        *,
        current_page: int | None = None,
        limit: int = 3,
        include_current: bool = False,
    ) -> list[RelevantPage]:
        pages = self.read_all_pages(pdf_path)
        if not pages or not query.strip():
            return []

        terms = _expand_query_terms(query)
        if not terms:
            return []

        current = int(current_page or 0)
        candidates: list[RelevantPage] = []
        for index, text in enumerate(pages):
            page_number = index + 1
            if not include_current and current == page_number:
                continue
            score, matched = _score_page(text, terms)
            if score <= 0:
                continue
            candidates.append(RelevantPage(
                page=page_number,
                text=text,
                score=score,
                matched_terms=tuple(matched[:8]),
            ))

        candidates.sort(key=lambda page: (-page.score, abs(page.page - current) if current else 0, page.page))
        return candidates[:max(0, limit)]

    def _load_pages(self, path: Path) -> list[str]:
        stat = path.stat()
        key = str(path.resolve())
        cached = self._cache.get(key)
        if (
            cached
            and cached.get("mtime_ns") == stat.st_mtime_ns
            and cached.get("size") == stat.st_size
        ):
            return list(cached["pages"])

        reader = PdfReader(str(path))
        pages = [(page.extract_text() or "").strip() for page in reader.pages]
        self._cache[key] = {
            "mtime_ns": stat.st_mtime_ns,
            "size": stat.st_size,
            "pages": pages,
        }
        return pages


_WORD_RE = re.compile(r"[a-z0-9가-힣]+")

_DOMAIN_SYNONYMS: dict[str, tuple[str, ...]] = {
    "흐름제어": ("흐름 제어", "flow control", "receive window", "receiver window", "rcvwindow", "rwnd", "수신 윈도우", "수신 버퍼", "buffer overflow"),
    "흐름": ("흐름 제어", "flow control"),
    "flow": ("flow control", "receive window", "rwnd"),
    "control": ("flow control", "congestion control"),
    "tcp": ("transmission control protocol", "reliable data transfer", "ack", "sequence number", "retransmission", "checksum", "timeout", "rtt"),
    "신뢰성": ("신뢰할 수 있는 데이터 전송", "reliable data transfer", "rdt", "ack", "sequence number", "retransmission", "checksum", "timeout", "fast retransmit", "duplicate ack"),
    "신뢰": ("신뢰할 수 있는 데이터 전송", "reliable data transfer", "ack", "retransmission", "checksum"),
    "reliable": ("reliable data transfer", "rdt", "ack", "sequence number", "retransmission"),
    "ack": ("ack", "acknowledgement", "acknowledgment", "duplicate ack", "cumulative ack"),
    "재전송": ("retransmission", "timeout", "fast retransmit", "duplicate ack"),
    "혼잡제어": ("혼잡 제어", "congestion control", "congestion window", "cwnd"),
    "혼잡": ("congestion control", "congestion window", "cwnd"),
    "다중화": ("multiplexing", "demultiplexing", "port number", "socket"),
    "역다중화": ("demultiplexing", "multiplexing", "port number", "socket"),
    "udp": ("user datagram protocol", "connectionless", "best effort"),
}


def _normalize_text(value: str) -> str:
    return " ".join(_WORD_RE.findall(value.lower()))


def _expand_query_terms(query: str) -> list[str]:
    normalized = _normalize_text(query)
    terms: set[str] = set()
    for token in _WORD_RE.findall(query.lower()):
        if len(token) >= 2:
            terms.add(token)
        collapsed = token.replace(" ", "")
        for key, values in _DOMAIN_SYNONYMS.items():
            if key in collapsed:
                terms.add(key)
                terms.update(values)
    if "흐름" in normalized and "제어" in normalized:
        terms.add("흐름제어")
        terms.update(_DOMAIN_SYNONYMS["흐름제어"])
    if "혼잡" in normalized and "제어" in normalized:
        terms.add("혼잡제어")
        terms.update(_DOMAIN_SYNONYMS["혼잡제어"])
    if "신뢰" in normalized:
        terms.update(_DOMAIN_SYNONYMS["신뢰"])
    return sorted({term.strip().lower() for term in terms if term.strip()}, key=lambda item: (-len(item), item))


def _score_page(text: str, terms: list[str]) -> tuple[float, list[str]]:
    normalized = _normalize_text(text)
    if not normalized:
        return 0.0, []

    score = 0.0
    matched: list[str] = []
    for term in terms:
        normalized_term = _normalize_text(term)
        if not normalized_term:
            continue
        count = normalized.count(normalized_term)
        if count <= 0:
            continue
        matched.append(term)
        weight = 3.0 if " " in normalized_term else 1.0
        if len(normalized_term) >= 8:
            weight += 1.0
        score += min(count, 6) * weight

    # Prefer pages that match several distinct question-specific concepts over
    # overview pages that repeat one broad term many times.
    if len(matched) >= 2:
        score += (len(matched) - 1) * 4.0
    if any(term in {"rcvwindow", "rwnd", "receive window", "수신 윈도우"} for term in matched):
        score += 4.0

    if matched and len(normalized) < 350:
        score *= 0.85
    return score, matched


pdf_context_service = PdfContextService()
