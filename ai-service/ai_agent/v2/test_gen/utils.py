"""
Shared utilities for LectureTestGenerator.
"""
import os
import asyncio
import pathlib
from typing import Any, Union

from dotenv import load_dotenv
from google import genai
from google.genai import types


load_dotenv()
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")

if not GEMINI_API_KEY:
    base_path = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    env_path = os.path.join(base_path, ".env")
    load_dotenv(env_path)
    GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")


async def load_lecture_material(file_path_or_content: str, client: genai.Client) -> Union[str, types.Part]:
    """
    Load lecture material as inline text or a Gemini PDF Part.

    Args:
        file_path_or_content: File path or raw text content.
        client: Gemini client instance.

    Returns:
        - PDF file path: types.Part
        - Text file path or raw text: str
    """
    if _looks_like_inline_content(file_path_or_content):
        return file_path_or_content

    try:
        path = pathlib.Path(file_path_or_content)
    except (OSError, ValueError):
        return file_path_or_content

    try:
        is_file = path.is_file()
    except OSError:
        return file_path_or_content

    if not is_file:
        return file_path_or_content

    suffix = path.suffix.lower()
    if suffix == ".pdf":
        uploaded = await asyncio.to_thread(client.files.upload, file=path)
        uri = getattr(uploaded, "uri", None)
        if not uri:
            raise RuntimeError("PDF upload did not return a Gemini file URI.")
        mime_type = getattr(uploaded, "mime_type", None) or "application/pdf"
        return types.Part.from_uri(file_uri=uri, mime_type=mime_type)

    if suffix in [".txt", ".md", ".py", ".json"]:
        try:
            return await asyncio.to_thread(path.read_text, encoding="utf-8")
        except UnicodeDecodeError:
            return await asyncio.to_thread(path.read_text, encoding="cp949")

    try:
        return await asyncio.to_thread(path.read_text, encoding="utf-8")
    except Exception as exc:
        raise ValueError(f"Unsupported file format: {suffix}") from exc


def build_lecture_material_contents(
    lecture_material: Union[str, types.Part],
    *,
    text_limit: int,
    label: str = "[Lecture Material]",
) -> list[Any]:
    if isinstance(lecture_material, str):
        return [f"{label}\n{lecture_material[:text_limit]}"]
    if isinstance(lecture_material, types.Part):
        return [
            f"{label}\nAttached PDF lecture material. Use this PDF as the only factual source.",
            lecture_material,
        ]
    raise TypeError(f"Unsupported lecture material type: {type(lecture_material).__name__}")


def _looks_like_inline_content(value: str) -> bool:
    """
    Avoid treating multiline or very long lecture text as a filesystem path.
    """
    if not isinstance(value, str):
        return False
    if not value:
        return False
    if "\x00" in value:
        return True
    if "\n" in value or "\r" in value:
        return True
    if len(value) > 240:
        return True
    return False


def get_gemini_client(api_key: str = None) -> genai.Client:
    key = api_key or GEMINI_API_KEY
    if not key:
        raise ValueError("GEMINI_API_KEY is not configured.")
    return genai.Client(api_key=key)
