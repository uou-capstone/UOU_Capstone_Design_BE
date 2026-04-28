# -*- coding: utf-8 -*-
"""
path_validator.py

Shared utility for validating user-supplied file paths.
Prevents path traversal attacks by ensuring all paths stay within the uploads root.
"""
from __future__ import annotations

import pathlib

from fastapi import HTTPException

# All uploaded files must be inside this directory.
UPLOADS_ROOT: pathlib.Path = pathlib.Path("uploads").resolve()


def validate_pdf_path(pdf_path: str) -> str:
    """
    Validates that pdf_path is inside UPLOADS_ROOT and the file exists.

    Raises:
        HTTPException(400): path is outside uploads root or traversal detected
        HTTPException(404): file does not exist

    Returns:
        Absolute path string safe to pass to agents.
    """
    if not pdf_path:
        raise HTTPException(status_code=400, detail="pdf_path is required.")

    try:
        resolved = (UPLOADS_ROOT / pdf_path).resolve()
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid pdf_path.")

    # Reject anything outside the uploads directory
    try:
        resolved.relative_to(UPLOADS_ROOT)
    except ValueError:
        raise HTTPException(
            status_code=400,
            detail="pdf_path must be inside the uploads directory.",
        )

    if not resolved.exists():
        raise HTTPException(status_code=404, detail=f"File not found: {pdf_path}")

    return str(resolved)


def validate_pdf_path_optional(pdf_path: str | None) -> str | None:
    """Same as validate_pdf_path but returns None if pdf_path is None/empty."""
    if not pdf_path:
        return None
    return validate_pdf_path(pdf_path)
