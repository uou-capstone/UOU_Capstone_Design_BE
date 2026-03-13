import os
import re
import json
import pathlib
from typing import List, Tuple, TypedDict, Optional

from dotenv import load_dotenv
from google import genai
from google.genai import types
from PyPDF2 import PdfReader, PdfWriter

load_dotenv()
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")


PDF_CHAPTERS_SYSTEM_PROMPT = """당신은 **문서 구조 분석 전문가**입니다.
주어진 PDF 강의 자료를 분석하여, 강의를 진행하기 적합한 **챕터(소주제) 리스트**를 추출해주세요.

### 출력 형식 (Strict JSON Array)
아래 필드가 반드시 포함된 JSON 배열로만 출력하세요.
[
  {"title": "챕터 제목", "start_page": 1, "end_page": 3},
  {"title": "다음 챕터", "start_page": 4, "end_page": 7}
]

### 제약/주의사항 (매우 중요)
- 페이지는 **1부터 시작**합니다.
- start_page <= end_page 를 만족해야 합니다.
- 챕터는 너무 자잘하게 쪼개지 말고, 강의하기 좋은 굵직한 주제 위주로 **3~15개** 사이로 추출하세요.
- 누락된 부분이 없도록, 가능한 한 전체 PDF 페이지 범위를 커버하세요.
"""


class PdfChapter(TypedDict):
    title: str
    start_page: int
    end_page: int


def _safe_filename(name: str, max_len: int = 80) -> str:
    name = name.strip()
    name = re.sub(r"[^\w\-.가-힣\s]", "", name)
    name = re.sub(r"\s+", "_", name)
    name = name.strip("._")
    if not name:
        return "chapter"
    return name[:max_len]


def _read_text_any_encoding(path: pathlib.Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return path.read_text(encoding="cp949")


def analyze_pdf_chapters(pdf_path: str) -> List[PdfChapter]:
    """Gemini를 사용하여 PDF의 챕터 제목/페이지 범위를 추출합니다."""
    client = genai.Client(api_key=GEMINI_API_KEY)
    path = pathlib.Path(pdf_path)
    if path.suffix.lower() != ".pdf":
        raise ValueError("analyze_pdf_chapters는 .pdf만 지원합니다.")

    content_part = types.Part.from_bytes(
        data=path.read_bytes(),
        mime_type="application/pdf",
    )

    response = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=[PDF_CHAPTERS_SYSTEM_PROMPT, content_part],
        config=types.GenerateContentConfig(response_mime_type="application/json"),
    )

    try:
        data = json.loads(response.text)
    except Exception as e:
        raise ValueError(f"PDF 챕터 JSON 파싱 실패: {e}")

    if not isinstance(data, list):
        raise ValueError("PDF 챕터 결과가 JSON 배열이 아닙니다.")

    chapters: List[PdfChapter] = []
    for item in data:
        if not isinstance(item, dict):
            continue
        title = str(item.get("title", "")).strip() or "전체 내용"
        try:
            sp = int(item.get("start_page"))
            ep = int(item.get("end_page"))
        except Exception:
            continue
        chapters.append({"title": title, "start_page": sp, "end_page": ep})

    if not chapters:
        raise ValueError("추출된 챕터가 없습니다.")
    return chapters


def split_pdf_by_chapters_node(pdf_path: str, chapters: List[PdfChapter]) -> List[Tuple[str, str]]:
    """
    PyPDF2로 PDF를 챕터별 물리 파일로 분할하여 저장합니다.
    Returns: [(챕터제목, 분할된_pdf_경로), ...]
    """
    src = pathlib.Path(pdf_path)
    reader = PdfReader(str(src))
    total_pages = len(reader.pages)

    out_dir = src.parent / f"{src.stem}_chapters"
    out_dir.mkdir(parents=True, exist_ok=True)

    results: List[Tuple[str, str]] = []
    for idx, ch in enumerate(chapters, start=1):
        title = ch["title"].strip() or f"Chapter {idx}"
        start_page = max(1, int(ch["start_page"]))
        end_page = min(total_pages, int(ch["end_page"]))
        if start_page > end_page:
            continue

        writer = PdfWriter()
        for p in range(start_page - 1, end_page):
            writer.add_page(reader.pages[p])

        filename = f"{idx:02d}_{_safe_filename(title)}_p{start_page}-{end_page}.pdf"
        out_path = out_dir / filename
        with open(out_path, "wb") as f:
            writer.write(f)

        results.append((title, str(out_path)))

    if not results:
        return [(src.stem, str(src))]
    return results


def split_md_by_headings(md_path: str) -> List[Tuple[str, str]]:
    """
    헤딩(# 또는 ##)을 기준으로 마크다운을 챕터별 파일로 분할 저장합니다.
    Returns: [(챕터제목, 분할된_md_경로), ...]
    """
    src = pathlib.Path(md_path)
    text = _read_text_any_encoding(src)
    lines = text.splitlines()

    heading_re = re.compile(r"^(#{1,2})\s+(.+?)\s*$")

    sections: List[Tuple[str, List[str]]] = []
    current_title: Optional[str] = None
    current_lines: List[str] = []

    def flush():
        nonlocal current_title, current_lines
        if current_title is None:
            return
        content = "\n".join(current_lines).strip()
        if content:
            sections.append((current_title, current_lines[:]))
        current_lines = []

    for line in lines:
        m = heading_re.match(line)
        if m:
            flush()
            current_title = m.group(2).strip() or "전체 내용"
            current_lines = [line]
        else:
            if current_title is None:
                current_title = "전체 내용"
                current_lines = []
            current_lines.append(line)

    flush()

    if not sections:
        sections = [(src.stem, lines)]

    out_dir = src.parent / f"{src.stem}_chapters"
    out_dir.mkdir(parents=True, exist_ok=True)

    results: List[Tuple[str, str]] = []
    for idx, (title, sec_lines) in enumerate(sections, start=1):
        filename = f"{idx:02d}_{_safe_filename(title)}.md"
        out_path = out_dir / filename
        out_path.write_text("\n".join(sec_lines).rstrip() + "\n", encoding="utf-8")
        results.append((title, str(out_path)))

    return results


def main(file_path: str) -> List[Tuple[str, str]]:
    """
    메인 분석 함수
    Returns: [(챕터제목, 파일경로), (챕터제목, 파일경로), ...]
    """
    path = pathlib.Path(file_path)
    suffix = path.suffix.lower()

    print(f"[INFO] 문서 구조를 분석/분할 중입니다... ({path.name})")

    if suffix == ".pdf":
        try:
            chapters = analyze_pdf_chapters(str(path))
            return split_pdf_by_chapters_node(str(path), chapters)
        except Exception as e:
            print(f"[WARN] PDF 구조 분석/분할 실패 (원본 그대로 사용): {e}")
            return [(path.stem, str(path))]

    if suffix == ".md":
        try:
            return split_md_by_headings(str(path))
        except Exception as e:
            print(f"[WARN] MD 분할 실패 (원본 그대로 사용): {e}")
            return [(path.stem, str(path))]

    raise ValueError(f"지원하지 않는 파일 형식입니다: {suffix}")
