from __future__ import annotations

import hashlib
import json
import logging
import os
from pathlib import Path
from typing import Any

from fastapi import HTTPException

from ai_agent.bridge.GeminiBridgeClient import GeminiBridgeClient
from ai_agent.types.domain import SessionState
from app.core.path_validator import validate_pdf_path

logger = logging.getLogger(__name__)

_FILE_REF_MAX_BYTES = int(os.getenv("GEMINI_FILE_REF_MAX_BYTES", str(50 * 1024 * 1024)))
_FINGERPRINT_SAMPLE_BYTES = 64 * 1024


class PdfFileRefService:
    """
    Maintains a session-level Gemini Files API reference for the current PDF.

    Page text remains the primary source for page-scoped behavior. This service
    only adds the original PDF as a secondary source for QA when text extraction
    or semantic search misses a term.
    """

    def _safe_pdf_path(self, pdf_path: str) -> Path | None:
        if not pdf_path or not pdf_path.lower().endswith(".pdf"):
            return None
        try:
            path = Path(validate_pdf_path(pdf_path))
        except HTTPException:
            logger.warning("[FileAPI] QA fileRef path 검증 실패")
            return None
        except Exception as exc:
            logger.warning("[FileAPI] QA fileRef path 검증 오류: %s", exc)
            return None

        if not path.exists() or not path.is_file() or path.suffix.lower() != ".pdf":
            return None
        stat = path.stat()
        if stat.st_size <= 0 or stat.st_size > _FILE_REF_MAX_BYTES:
            logger.warning("[FileAPI] QA fileRef 크기 제한 초과 또는 빈 PDF: size=%d", stat.st_size)
            return None
        try:
            with path.open("rb") as handle:
                if handle.read(5) != b"%PDF-":
                    logger.warning("[FileAPI] QA fileRef PDF header 검증 실패")
                    return None
        except Exception as exc:
            logger.warning("[FileAPI] QA fileRef PDF header 조회 실패: %s", exc)
            return None
        return path

    def build_fingerprint(self, pdf_path: str) -> str | None:
        path = self._safe_pdf_path(pdf_path)
        if path is None:
            return None

        stat = path.stat()
        payload = {
            "path": str(path.resolve()),
            "size": stat.st_size,
            "mtimeNs": stat.st_mtime_ns,
            "sampleSha256": self._sample_hash(path, stat.st_size),
        }
        raw = json.dumps(payload, sort_keys=True, ensure_ascii=True)
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()

    def _sample_hash(self, path: Path, size: int) -> str:
        hasher = hashlib.sha256()
        with path.open("rb") as handle:
            hasher.update(handle.read(_FINGERPRINT_SAMPLE_BYTES))
            if size > _FINGERPRINT_SAMPLE_BYTES:
                handle.seek(max(0, size - _FINGERPRINT_SAMPLE_BYTES))
                hasher.update(handle.read(_FINGERPRINT_SAMPLE_BYTES))
        return hasher.hexdigest()

    async def ensure_file_part(
        self,
        bridge: GeminiBridgeClient,
        state: SessionState,
        pdf_path: str,
    ) -> Any | None:
        """
        Return a Gemini Part for the current PDF and update session cache fields.

        Failures are intentionally non-fatal: QA falls back to page text/index.
        """
        fingerprint = self.build_fingerprint(pdf_path)
        if not fingerprint:
            return None
        safe_path = self._safe_pdf_path(pdf_path)
        if safe_path is None:
            return None

        try:
            part, metadata = await bridge.load_pdf_file_ref_part(
                str(safe_path),
                fingerprint=fingerprint,
            )
        except Exception as exc:
            logger.warning("[FileAPI] QA fileRef 확보 실패: %s (%s)", pdf_path, exc)
            return None

        state.pdf_fingerprint = fingerprint
        state.gemini_file_ref = {
            **metadata,
            "fingerprint": fingerprint,
        }
        return part


pdf_file_ref_service = PdfFileRefService()
