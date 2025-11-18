import os
from dotenv import load_dotenv
import json
import pathlib
from typing import TypedDict, Dict, Any, List, Tuple
from google import genai
from google.genai import types
from PyPDF2 import PdfReader, PdfWriter
from langgraph.graph import StateGraph, END

load_dotenv()
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")


class PDFWorkflowState(TypedDict):
    pdf_path: str
    chapters_info: List[Dict[str, Any]]
    output_folder: str
    result_message: str


ANALYSIS_PROMPT = """당신은 PDF 파일의 구조를 분석하는 전문 AI 에이전트입니다.

주어진 PDF 콘텐츠를 분석하여 각 챕터의 제목과, 해당 챕터가 시작하고 끝나는 정확한 페이지 번호를 식별하는 것이 당신의 임무입니다.

반드시, PDF파일을 모두 잘 읽어야 합니다

문서의 목차, 제목 스타일, 전체 구조를 종합적으로 분석하여 챕터를 구분하세요. "서론", "결론", "부록" 등도 하나하나의 챕터로 취급해야 합니다.

분석 결과는 반드시 각 챕터의 "chapter_title", "start_page", "end_page"를 포함하는 JSON 배열 형식으로 반환해야 합니다. 응답에 다른 어떤 설명도 포함하지 마세요.

출력 형식 예시:

```json
[
  {
    "chapter_title": "1장: 서론",
    "start_page": 1,
    "end_page": 25
  },
  {
    "chapter_title": "2장: 본론",
    "start_page": 26,
    "end_page": 50
  }
]
```
"""


def analyze_pdf_structure_node(state: PDFWorkflowState) -> Dict[str, Any]:
    pdf_path = state["pdf_path"]
    filepath = pathlib.Path(pdf_path)
    
    if not filepath.exists():
        raise FileNotFoundError(f"PDF 파일을 찾을 수 없습니다: {pdf_path}")
    
    client = genai.Client(api_key=GEMINI_API_KEY)
    
    response = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=[
            types.Part.from_bytes(
                data=filepath.read_bytes(),
                mime_type='application/pdf',
            ),
            ANALYSIS_PROMPT
        ]
    )
    
    response_text = response.text.strip()
    
    if "```json" in response_text:
        json_start = response_text.find("```json") + 7
        json_end = response_text.find("```", json_start)
        json_text = response_text[json_start:json_end].strip()
    elif "```" in response_text:
        json_start = response_text.find("```") + 3
        json_end = response_text.find("```", json_start)
        json_text = response_text[json_start:json_end].strip()
    else:
        json_text = response_text
    
    chapters_info = json.loads(json_text)
    
    return {
        "chapters_info": chapters_info,
        "result_message": f"챕터 {len(chapters_info)}개 분석 완료"
    }


def split_pdf_by_chapters_node(state: PDFWorkflowState) -> Dict[str, Any]:
    pdf_path = state["pdf_path"]
    chapters_info = state["chapters_info"]
    
    pdf_filename = pathlib.Path(pdf_path).stem
    pdf_dir = pathlib.Path(pdf_path).parent
    output_folder = pdf_dir / "component" / pdf_filename
    output_folder.mkdir(parents=True, exist_ok=True)
    
    pdf_reader = PdfReader(pdf_path)
    total_pages = len(pdf_reader.pages)
    
    for chapter in chapters_info:
        chapter_title = chapter["chapter_title"]
        start_page = chapter["start_page"]
        end_page = chapter["end_page"]
        
        safe_title = "".join(c if c.isalnum() or c in (' ', '-', '_') else '_' for c in chapter_title)
        output_filename = f"{safe_title}.pdf"
        output_path = output_folder / output_filename
        
        pdf_writer = PdfWriter()
        
        for page_num in range(start_page - 1, end_page):
            if page_num < total_pages:
                pdf_writer.add_page(pdf_reader.pages[page_num])
        
        with open(output_path, 'wb') as output_file:
            pdf_writer.write(output_file)
    
    return {
        "output_folder": str(output_folder),
        "result_message": f"총 {len(chapters_info)}개 챕터 분할 완료"
    }


def create_workflow():
    workflow = StateGraph(PDFWorkflowState)
    
    workflow.add_node("analyze_structure", analyze_pdf_structure_node)
    workflow.add_node("split_pdf", split_pdf_by_chapters_node)
    
    workflow.set_entry_point("analyze_structure")
    workflow.add_edge("analyze_structure", "split_pdf")
    workflow.add_edge("split_pdf", END)
    
    return workflow.compile()


def main(pdf: str) -> List[Tuple[str, str]]:
    """
    PDF 파일을 분석하고 챕터별로 분할
    """
    app = create_workflow()
    
    initial_state = {
        "pdf_path": pdf,
        "chapters_info": [],
        "output_folder": "",
        "result_message": ""
    }
    
    final_state = app.invoke(initial_state)
    
    output_list = []
    output_folder = pathlib.Path(final_state['output_folder'])
    
    for chapter in final_state['chapters_info']:
        chapter_title = chapter['chapter_title']
        safe_title = "".join(c if c.isalnum() or c in (' ', '-', '_') else '_' for c in chapter_title)
        pdf_filename = f"{safe_title}.pdf"
        pdf_path = str(output_folder / pdf_filename)
        output_list.append((chapter_title, pdf_path))
    
    return output_list


def analyze_only(pdf: str) -> List[Dict[str, Any]]:
    """
    PDF 구조만 분석하고 분할하지 않음 (스트리밍 모드용)
    
    Args:
        pdf (str): PDF 파일 경로
    
    Returns:
        List[Dict]: [{"chapter_title": str, "start_page": int, "end_page": int}, ...]
    """
    filepath = pathlib.Path(pdf)
    
    if not filepath.exists():
        raise FileNotFoundError(f"PDF 파일을 찾을 수 없습니다: {pdf}")
    
    client = genai.Client(api_key=GEMINI_API_KEY)
    
    response = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=[
            types.Part.from_bytes(
                data=filepath.read_bytes(),
                mime_type='application/pdf',
            ),
            ANALYSIS_PROMPT
        ]
    )
    
    response_text = response.text.strip()
    
    # JSON 추출
    if "```json" in response_text:
        json_start = response_text.find("```json") + 7
        json_end = response_text.find("```", json_start)
        json_text = response_text[json_start:json_end].strip()
    elif "```" in response_text:
        json_start = response_text.find("```") + 3
        json_end = response_text.find("```", json_start)
        json_text = response_text[json_start:json_end].strip()
    else:
        json_text = response_text
    
    chapters_info = json.loads(json_text)
    return chapters_info


def split_single_chapter(
    pdf_path: str,
    chapter_info: Dict[str, Any],
    chapter_index: int
) -> str:
    """
    PDF에서 특정 챕터 하나만 추출 (스트리밍 모드용)
    
    Args:
        pdf_path (str): 원본 PDF 파일 경로
        chapter_info (Dict): {"chapter_title": str, "start_page": int, "end_page": int}
        chapter_index (int): 챕터 인덱스
    
    Returns:
        str: 추출된 챕터 PDF 파일 경로
    """
    pdf_filename = pathlib.Path(pdf_path).stem
    pdf_dir = pathlib.Path(pdf_path).parent
    output_folder = pdf_dir / "component" / pdf_filename
    output_folder.mkdir(parents=True, exist_ok=True)
    
    chapter_title = chapter_info["chapter_title"]
    start_page = chapter_info["start_page"]
    end_page = chapter_info["end_page"]
    
    # 안전한 파일명 생성 (원래 방식 유지)
    safe_title = "".join(c if c.isalnum() or c in (' ', '-', '_') else '_' for c in chapter_title)
    output_filename = f"{safe_title}.pdf"
    output_path = output_folder / output_filename
    
    # 이미 존재하면 재사용
    if output_path.exists():
        return str(output_path)
    
    # PDF 분할
    pdf_reader = PdfReader(pdf_path)
    total_pages = len(pdf_reader.pages)
    pdf_writer = PdfWriter()
    
    for page_num in range(start_page - 1, end_page):
        if page_num < total_pages:
            pdf_writer.add_page(pdf_reader.pages[page_num])
    
    with open(output_path, 'wb') as output_file:
        pdf_writer.write(output_file)
    
    return str(output_path)


