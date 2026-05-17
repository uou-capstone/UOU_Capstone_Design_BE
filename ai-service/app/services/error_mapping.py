from __future__ import annotations

from typing import Literal

from fastapi import HTTPException


StableErrorType = Literal[
    "VALIDATION_ERROR",
    "AUTH_ERROR",
    "AI_TIMEOUT",
    "AI_QUOTA",
    "AI_UNAVAILABLE",
    "INTERNAL_ERROR",
]


def stable_error_type(exc: BaseException) -> StableErrorType:
    if isinstance(exc, HTTPException):
        if exc.status_code in {400, 404, 409, 413, 422}:
            return "VALIDATION_ERROR"
        if exc.status_code in {401, 403}:
            return "AUTH_ERROR"
        return "INTERNAL_ERROR"

    text = f"{type(exc).__name__} {exc}".lower()
    if any(token in text for token in ("quota", "resource_exhausted", "429", "rate limit")):
        return "AI_QUOTA"
    if any(token in text for token in ("timeout", "deadline", "timed out")):
        return "AI_TIMEOUT"
    if any(token in text for token in ("connect", "connection", "unavailable", "503", "502", "504")):
        return "AI_UNAVAILABLE"
    if isinstance(exc, (ValueError, TypeError)):
        return "VALIDATION_ERROR"
    return "INTERNAL_ERROR"
