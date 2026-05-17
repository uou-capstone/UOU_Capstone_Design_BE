from __future__ import annotations

import asyncio
import json
import os
import re
from functools import lru_cache
from typing import Any


def model_name(requested: str | None = None) -> str:
    return requested or os.getenv("MODEL_NAME") or os.getenv("GEMINI_MODEL") or "gemini-2.5-flash"


def api_key() -> str:
    return os.getenv("GOOGLE_API_KEY") or os.getenv("GEMINI_API_KEY") or ""


@lru_cache(maxsize=8)
def _client(api_key_value: str):
    from google import genai

    return genai.Client(api_key=api_key_value)


def extract_json(text: str) -> dict[str, Any]:
    stripped = text.strip()
    if stripped.startswith("```"):
        stripped = re.sub(r"^```(?:json)?", "", stripped, flags=re.IGNORECASE).strip()
        stripped = re.sub(r"```$", "", stripped).strip()
    try:
        return json.loads(stripped)
    except json.JSONDecodeError:
        start = stripped.find("{")
        end = stripped.rfind("}")
        if start >= 0 and end > start:
            return json.loads(stripped[start:end + 1])
        raise


async def generate_json(
    *,
    prompt: str,
    model: str,
    response_json_schema: dict[str, Any] | None = None,
) -> dict[str, Any]:
    key = api_key()
    if not key:
        raise RuntimeError("gemini_api_key_missing")

    from google.genai import types

    config_kwargs: dict[str, Any] = {"response_mime_type": "application/json"}
    if response_json_schema:
        config_kwargs["response_json_schema"] = response_json_schema

    client = _client(key)
    response = await asyncio.to_thread(
        client.models.generate_content,
        model=model,
        contents=[prompt],
        config=types.GenerateContentConfig(**config_kwargs),
    )
    text = getattr(response, "text", None) or str(response)
    return extract_json(text)


async def generate_text(*, prompt: str, model: str) -> str:
    key = api_key()
    if not key:
        raise RuntimeError("gemini_api_key_missing")

    client = _client(key)
    response = await asyncio.to_thread(
        client.models.generate_content,
        model=model,
        contents=[prompt],
    )
    text = getattr(response, "text", None)
    if not text:
        raise RuntimeError("gemini_empty_response")
    return text.strip()
