from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
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


pdf_context_service = PdfContextService()
