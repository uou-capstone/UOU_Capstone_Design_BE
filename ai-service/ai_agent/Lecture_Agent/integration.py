import sys
import asyncio
import time
import re
import pathlib
from typing import List, Tuple, Dict, Any

from ai_agent.Lecture_Agent.component import PdfAnalysis, MainLectureAgent, MainQandAAgent

pdf_analysis_main = PdfAnalysis.main
lecture_agent_main = MainLectureAgent.main
qa_agent_main = MainQandAAgent.main


def print_streaming(text: str, delay: float = 0.01):
    """
    텍스트를 문장 단위로 스트리밍하듯이 출력
    [성능 개선]: 기본 delay를 0.1 -> 0.01로 단축하여 출력 병목을 줄임
    """
    sentences = re.split(r'([.!?]\s+|[.!?]$|\n)', text)

    for i in range(0, len(sentences), 2):
        if i < len(sentences):
            sentence = sentences[i]
            if i + 1 < len(sentences):
                sentence += sentences[i + 1]

            print(sentence, end='', flush=True)
            if delay > 0:
                time.sleep(delay)
    print()


def extract_questions(text: str) -> List[Tuple[int, int, str]]:
    """텍스트에서 [질문] [/질문] 토큰을 찾아 위치와 질문 내용을 반환"""
    pattern = r'\[질문\](.*?)\[/질문\]'
    questions: List[Tuple[int, int, str]] = []

    for match in re.finditer(pattern, text, re.DOTALL):
        start_pos = match.start()
        end_pos = match.end()
        question_content = match.group(1).strip()
        questions.append((start_pos, end_pos, question_content))

    return questions


# =============================================================================
# [FastAPI / Delegator 연동을 위한 API 인터페이스 함수들]
# delegator.py에서 호출하는 함수들입니다.
# =============================================================================

def initialize_lecture(pdf_path: str):
    """PDF를 분석하여 챕터 정보를 반환합니다."""
    chapters_info = pdf_analysis_main(pdf_path)
    return chapters_info, pdf_path


def prepare_lecture_content(pdf_path: str):
    """초기 세션 구성을 위해 챕터 기본 구조를 반환합니다."""
    chapters_info, _ = initialize_lecture(pdf_path)
    chapters = []
    for title, path in chapters_info:
        chapters.append({
            "chapterTitle": title,
            "pdfPath": path,
            "questions": {}
        })
    return {"chapters": chapters}


def build_segments_from_explanation(explanation: str, prefix: str = ""):
    """설명 텍스트를 질문과 일반 스크립트 세그먼트로 분리합니다."""
    questions = extract_questions(explanation)
    segments = []
    question_meta = {}

    current_pos = 0
    q_idx = 0

    for start_pos, end_pos, q_text in questions:
        # 질문 이전의 일반 텍스트 (스크립트)
        if start_pos > current_pos:
            script_text = explanation[current_pos:start_pos].strip()
            if script_text:
                segments.append({"type": "script", "content": script_text})

        # 질문 텍스트
        q_id = f"{prefix}q{q_idx}"
        segments.append({"type": "question", "question": q_text, "questionId": q_id})
        question_meta[q_id] = {
            "question": q_text,
            "questionIndex": q_idx
        }
        q_idx += 1
        current_pos = end_pos

    # 남은 일반 텍스트
    if current_pos < len(explanation):
        script_text = explanation[current_pos:].strip()
        if script_text:
            segments.append({"type": "script", "content": script_text})

    return segments, question_meta


def generate_single_chapter(pdf_path: str, chapter_info: tuple, current_chapter_idx: int):
    """
    단일 챕터의 스크립트를 생성하고 세그먼트로 파싱합니다.

    chapter_info는 다음 두 형태를 모두 허용합니다.
    - (chapter_title, chapter_pdf_path)
    - (chapter_title, chapter_pdf_path, start_page, end_page)
    """
    chapter_title = chapter_info[0] if len(chapter_info) > 0 else None
    chapter_pdf_path = chapter_info[1] if len(chapter_info) > 1 else None
    result_dict = lecture_agent_main(chapter_title, chapter_pdf_path)
    explanation = result_dict.get(chapter_title, "")

    segments, question_meta = build_segments_from_explanation(explanation, prefix=f"c{current_chapter_idx}-")

    return {
        "chapterTitle": chapter_title,
        "explanation": explanation,
        "segments": segments,
        "questions": question_meta,
        "pdfPath": chapter_pdf_path
    }


def get_next_segment(chapter_data: dict, current_segment_idx: int):
    """다음 세그먼트(스크립트 또는 질문)를 반환합니다."""
    segments = chapter_data.get("segments", [])
    if current_segment_idx < len(segments):
        return segments[current_segment_idx], current_segment_idx + 1
    return None, current_segment_idx


def generate_supplementary_explanation(question_text: str, user_answer: str, pdf_path: str):
    """사용자의 답변을 평가하고 보충 설명을 생성합니다."""
    qa_input = [(question_text, user_answer), pdf_path]
    explanation_result = qa_agent_main(qa_input)

    return {
        "supplementary_explanation": explanation_result,
        "validation_result": "평가 완료",
        "concept_queue": [],
        "bad_mode_history": [],
        "needs_follow_up": False,
        "state": "GOOD"
    }


def run_full_pipeline(pdf_path: str, skip_qa: bool = True, cancellation_callback=None):
    """백그라운드에서 전체 파이프라인을 실행하고 결과를 반환합니다."""
    chapters_info = pdf_analysis_main(pdf_path)
    lecture_results = []

    for i, (chapter_title, chapter_pdf_path) in enumerate(chapters_info):
        if cancellation_callback:
            cancellation_callback()

        result_dict = lecture_agent_main(chapter_title, chapter_pdf_path)
        lecture_results.append(result_dict)

    return chapters_info, lecture_results


# =============================================================================
# [로컬 테스트용 비동기 파이프라인]
# =============================================================================

async def process_explanation_with_qa_async(explanation: str, chapter_title: str, pdf_path: str):
    """
    질문을 띄우자마자 모범답안 생성을 Prefetch로 시작하고,
    사용자의 입력을 기다리는 동안 백그라운드 작업이 진행되도록 구성.
    """
    questions = extract_questions(explanation)

    if not questions:
        print_streaming(explanation)
        return

    current_pos = 0

    for start_pos, end_pos, question_content in questions:
        before_question = explanation[current_pos:start_pos]
        if before_question.strip():
            print_streaming(before_question)

        # 질문 출력
        print("\n" + "=" * 60)
        print("[질문]")
        print(question_content)
        print("=" * 60)

        # Prefetch: 사용자가 답변을 쓰는 동안 모범 답안 생성
        prefetch_task = asyncio.create_task(
            MainQandAAgent.generate_model_answer_async(question_content, pdf_path)
        )
        print("\n(답변을 입력하는 동안 채점 준비를 진행합니다...)")

        # input()은 blocking이므로 별도 스레드에서 실행
        user_answer = await asyncio.to_thread(input, "\n답변을 입력하세요: ")
        print("\n")

        print("답변 분석 중...\n")

        model_answer = await prefetch_task
        final_explanation = await MainQandAAgent.run_qa_flow_async(
            question_content,
            user_answer,
            pdf_path,
            precomputed_model_answer=model_answer,
        )

        print("\n" + "=" * 60)
        print("[분석 결과]")
        print("=" * 60 + "\n")
        print_streaming(final_explanation)
        print("\n" + "=" * 60 + "\n")

        current_pos = end_pos

    remaining = explanation[current_pos:]
    if remaining.strip():
        print_streaming(remaining)


async def run_lecture_agent_async(chapter_title: str, pdf_path: str) -> Dict[str, str]:
    """
    단일 챕터에 대해 강의 에이전트를 비동기적으로 실행 (Wrapper)
    동기 함수(lecture_agent_main)를 별도 스레드에서 실행하여 이벤트 루프 차단을 방지.
    """
    return await asyncio.to_thread(lecture_agent_main, chapter_title, pdf_path)


async def main_async(file_path: str, skip_qa: bool = False):
    """
    비동기 메인 로직:
    1. 모든 챕터 생성을 동시에 시작 (Parallel Execution)
    2. 결과는 순서대로 도착하는 즉시 출력 (Ordered Streaming)
    """
    print("=" * 60)
    print("교육 에이전트 시스템을 시작합니다")
    print("=" * 60 + "\n")

    # 1. 문서 구조 분석 (PDF/Markdown 모두 PdfAnalysis에 위임)
    lower_path = file_path.lower()

    if lower_path.endswith((".pdf", ".md")):
        print("[INFO] 문서 구조를 분석하고 챕터 리스트를 생성합니다...\n")
        chapters_info = pdf_analysis_main(file_path)
    else:
        print("[ERROR] 지원하지 않는 파일 형식입니다.")
        return [], []

    print(f"총 {len(chapters_info)}개의 챕터를 발견했습니다.\n")
    for i, (title, _) in enumerate(chapters_info, 1):
        print(f"  {i}. {title}")
    print("\n")

    # ---------------------------------------------------------
    # 병렬 실행 파이프라인 구축
    # ---------------------------------------------------------
    print("강의 생성 작업을 백그라운드에서 동시에 시작합니다...\n")

    tasks: List[asyncio.Task] = []
    for chapter_title, chapter_pdf_path in chapters_info:
        task = asyncio.create_task(
            run_lecture_agent_async(chapter_title, chapter_pdf_path)
        )
        tasks.append(task)

    lecture_results_all: Dict[str, str] = {}

    # 순서대로 결과를 기다림 (Ordered Waiting)
    for i, task in enumerate(tasks):
        chapter_title = chapters_info[i][0]
        chapter_path = chapters_info[i][1]

        if not task.done():
            print(f"챕터 '{chapter_title}' 생성 중... 잠시만 기다려주세요.\n")

        lecture_data = await task
        lecture_results_all.update(lecture_data)

        # 출력 로직
        print("\n" + "=" * 60)
        print(f"Chapter {i + 1}: {chapter_title}")
        print("=" * 60 + "\n")

        explanation = lecture_data.get(chapter_title, "")

        if not skip_qa:
            await process_explanation_with_qa_async(explanation, chapter_title, chapter_path)
        else:
            print_streaming(explanation)

        # 다음 챕터 안내
        if i < len(tasks) - 1:
            print("\n" + "-" * 40)
            print("다음 챕터로 이동합니다...")
            print("-" * 40 + "\n")

    print("\n" + "=" * 60)
    print("모든 강의가 완료되었습니다!")
    print("=" * 60)

    return chapters_info, [lecture_results_all]


def main(file_path: str, skip_qa: bool = False):
    """엔트리 포인트: 비동기 루프 실행"""
    import traceback

    try:
        return asyncio.run(main_async(file_path, skip_qa))
    except FileNotFoundError as e:
        print(f"[ERROR] 파일을 찾을 수 없습니다 - {e}")
        sys.exit(1)
    except Exception as e:
        print(f"[ERROR] 오류가 발생했습니다: {e}")
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("사용법: python integration.py <파일 경로>")
        sys.exit(1)

    file_path_arg = sys.argv[1]
    main(file_path_arg)

