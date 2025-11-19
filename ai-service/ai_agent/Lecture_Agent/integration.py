import sys
import asyncio
import time
import re
from typing import List, Tuple, Dict, Any, Optional
from ai_agent import PdfAnalysis, MainLectureAgent, MainQandAAgent

pdf_analysis_main = PdfAnalysis.main
pdf_analysis_only = PdfAnalysis.analyze_only
pdf_split_single_chapter = PdfAnalysis.split_single_chapter
lecture_agent_main = MainLectureAgent.main
qa_agent_main = MainQandAAgent.main


def print_streaming(text: str, delay: float = 0.1):
    """텍스트를 문장 단위로 스트리밍하듯이 출력"""
    # 문장 단위로 분리 (마침표, 느낌표, 물음표 기준)
    sentences = re.split(r'([.!?]\s+|[.!?]$|\n)', text)
    
    for i in range(0, len(sentences), 2):
        if i < len(sentences):
            sentence = sentences[i]
            if i + 1 < len(sentences):
                sentence += sentences[i + 1]
            
            print(sentence, end='', flush=True)
            time.sleep(delay)
    
    print()  # 마지막 줄바꿈


def extract_questions(text: str) -> List[Tuple[int, int, str]]:
    """텍스트에서 [질문] [/질문] 토큰을 찾아 위치와 질문 내용을 반환"""
    pattern = r'\[질문\](.*?)\[/질문\]'
    questions = []
    
    for match in re.finditer(pattern, text, re.DOTALL):
        start_pos = match.start()
        end_pos = match.end()
        question_content = match.group(1).strip()
        questions.append((start_pos, end_pos, question_content))
    
    return questions


def build_segments_from_explanation(
    explanation: str,
    prefix: str = ""
) -> Tuple[List[Dict[str, Any]], Dict[str, Dict[str, Any]]]:
    """
    강의 설명문을 스크립트/질문 세그먼트로 분리한다.
    """
    questions = extract_questions(explanation)
    segments: List[Dict[str, Any]] = []
    question_meta: Dict[str, Dict[str, Any]] = {}

    if not questions:
        stripped = explanation.strip()
        if stripped:
            segments.append({"type": "script", "content": stripped})
        return segments, question_meta

    current_pos = 0
    for idx, (start_pos, end_pos, question_content) in enumerate(questions):
        before_question = explanation[current_pos:start_pos]
        if before_question.strip():
            segments.append({"type": "script", "content": before_question.strip()})

        question_id = f"{prefix}q-{idx}" if prefix else f"q-{idx}"
        cleaned_question = question_content.strip()
        segments.append({
            "type": "question",
            "questionId": question_id,
            "question": cleaned_question
        })
        question_meta[question_id] = {
            "question": cleaned_question,
            "questionIndex": idx
        }
        current_pos = end_pos

    remaining = explanation[current_pos:]
    if remaining.strip():
        segments.append({"type": "script", "content": remaining.strip()})

    return segments, question_meta


def process_explanation_with_qa(explanation: str, chapter_title: str, pdf_path: str):
    """설명문을 처리하면서 질문이 나오면 Q&A 에이전트를 호출"""
    questions = extract_questions(explanation)
    
    if not questions:
        # 질문이 없으면 그냥 전체 출력
        print_streaming(explanation)
        return
    
    # 질문이 있는 경우 구간별로 처리
    current_pos = 0
    
    for start_pos, end_pos, question_content in questions:
        # 질문 이전 부분 출력
        before_question = explanation[current_pos:start_pos]
        if before_question.strip():
            print_streaming(before_question)
        
        # 질문 출력
        print("\n" + "="*60)
        print(f"[질문]")
        print(question_content)
        print("="*60)
        
        # 사용자 답변 받기
        user_answer = input("\n답변을 입력하세요: ")
        print("\n")
        
        # Q&A 에이전트 호출
        print("답변을 분석하고 보충 설명을 생성하고 있습니다...\n")
        supplementary_explanation = qa_agent_main([
            (question_content, user_answer),
            pdf_path
        ])
        
        # 보충 설명 출력
        print("\n" + "="*60)
        print("[보충 설명]")
        print("="*60 + "\n")
        print_streaming(supplementary_explanation)
        print("\n" + "="*60 + "\n")
        
        current_pos = end_pos
    
    # 마지막 질문 이후 남은 부분 출력
    remaining = explanation[current_pos:]
    if remaining.strip():
        print_streaming(remaining)


async def run_lecture_agent(chapter_title: str, pdf_path: str) -> Dict[str, str]:
    """비동기로 강의 에이전트를 실행"""
    return await asyncio.to_thread(lecture_agent_main, chapter_title, pdf_path)


async def run_all_lecture_agents(chapters_info: List[Tuple[str, str]]) -> List[Dict[str, str]]:
    """모든 챕터에 대해 강의 에이전트를 동시에 실행"""
    tasks = []
    for chapter_title, pdf_path in chapters_info:
        task = run_lecture_agent(chapter_title, pdf_path)
        tasks.append(task)
    
    # 순서를 유지하면서 동시 실행
    results = await asyncio.gather(*tasks)
    return results


def prepare_lecture_content(pdf_path: str) -> Dict[str, Any]:
    """
    질문 인터랙션을 지원하기 위해 챕터별 스크립트/질문 구조를 생성한다.
    """
    chapters_info = pdf_analysis_main(pdf_path)
    lecture_results = asyncio.run(run_all_lecture_agents(chapters_info))

    structured_chapters = []
    for chapter_idx, ((chapter_title, chapter_pdf), lecture_dict) in enumerate(zip(chapters_info, lecture_results)):
        explanation = lecture_dict.get(chapter_title, "")
        segments, question_meta = build_segments_from_explanation(
            explanation,
            prefix=f"c{chapter_idx}-"
        )
        structured_chapters.append({
            "chapterTitle": chapter_title,
            "pdfPath": chapter_pdf,
            "segments": segments,
            "questions": question_meta
        })

    return {
        "chapters": structured_chapters
    }


def generate_supplementary_explanation(question: str, answer: str, pdf_path: str) -> Dict[str, Any]:
    """
    사용자 답변을 받아 Q&A 에이전트를 실행하고,
    보충 설명/평가 결과 등 구조화된 정보를 반환한다.
    """
    result = qa_agent_main([
        (question, answer),
        pdf_path
    ])

    # 구버전 호환: 문자열만 반환된 경우 최소한의 구조로 감싼다.
    if isinstance(result, str):
        return {
            "supplementary_explanation": result,
            "validation_result": "",
            "concept_queue": [],
            "current_concept_index": 0,
            "bad_mode_history": [],
            "state": "GOOD",
            "needs_follow_up": False
        }

    return result


# ==================== 스트리밍 모드용 함수 (새로 추가) ====================

def initialize_lecture(pdf_path: str) -> Tuple[List[Dict[str, Any]], str]:
    """
    PDF를 분석하여 챕터 정보만 반환 (강의는 생성하지 않음)
    
    Args:
        pdf_path (str): PDF 파일 경로
    
    Returns:
        Tuple[List[Dict], str]: (chapters_info, pdf_path)
            chapters_info: [{"chapter_title": str, "start_page": int, "end_page": int}, ...]
            pdf_path: 원본 PDF 경로 (그대로 반환)
    """
    chapters_info = pdf_analysis_only(pdf_path)
    return chapters_info, pdf_path


def generate_single_chapter(
    pdf_path: str,
    chapter_info: Dict[str, Any],
    chapter_index: int = 0
) -> Dict[str, Any]:
    """
    하나의 챕터에 대해서만 강의를 생성
    
    Args:
        pdf_path (str): 원본 PDF 파일 경로
        chapter_info (Dict): {"chapter_title": str, "start_page": int, "end_page": int}
        chapter_index (int): 챕터 인덱스
    
    Returns:
        Dict: {
            "chapterTitle": str,
            "pdfPath": str,  # 분할된 챕터 PDF 경로
            "segments": List[Dict],  # [{"type": "script", "content": "..."}, ...]
            "questions": Dict  # {"q-0": {"question": "...", "questionIndex": 0}}
        }
    """
    # 1. 특정 챕터만 PDF에서 추출
    chapter_pdf_path = pdf_split_single_chapter(pdf_path, chapter_info, chapter_index)
    
    # 2. 강의 생성
    chapter_title = chapter_info["chapter_title"]
    lecture_result = lecture_agent_main(chapter_title, chapter_pdf_path)
    explanation = lecture_result.get(chapter_title, "")
    
    # 3. 세그먼트로 분리 (질문 파싱 포함)
    segments, question_meta = build_segments_from_explanation(
        explanation,
        prefix=f"c{chapter_index}-"
    )
    
    return {
        "chapterTitle": chapter_title,
        "pdfPath": chapter_pdf_path,  # 분할된 챕터 PDF 경로
        "segments": segments,
        "questions": question_meta
    }


def get_next_segment(
    chapter_data: Dict[str, Any],
    current_segment_index: int
) -> Tuple[Optional[Dict], int]:
    """
    챕터 데이터에서 다음 세그먼트 하나만 반환
    
    Args:
        chapter_data (Dict): generate_single_chapter의 결과
        current_segment_index (int): 현재 읽은 세그먼트 인덱스
    
    Returns:
        Tuple[Optional[Dict], int]: (segment, next_index) 또는 (None, -1) if 챕터 끝
            segment: {"type": "script"|"question", "content"|"question": str, "questionId": str}
            next_index: 다음 세그먼트 인덱스 (-1이면 챕터 끝)
    """
    segments = chapter_data.get("segments", [])
    
    if current_segment_index >= len(segments):
        return None, -1  # 챕터 끝
    
    segment = segments[current_segment_index]
    return segment, current_segment_index + 1


def main(pdf_path: str, skip_qa: bool = False, cancellation_callback=None):
    """
    통합 에이전트 시스템의 메인 함수
    
    Args:
        pdf_path (str): 분석할 PDF 파일의 경로
        skip_qa (bool): Q&A 처리 건너뛰기 (API 호출 시 True)
        cancellation_callback (Callable): 호출 시 취소 여부를 확인하고 예외를 던지는 함수 (Optional)
    
    Returns:
        Tuple[List[Tuple[str, str]], List[Dict[str, str]]]: (chapters_info, lecture_results)
    """
    import traceback
    
    print("="*60)
    print("교육 에이전트 시스템을 시작합니다")
    print("="*60 + "\n")
    
    # 시작 전 취소 체크
    if cancellation_callback: cancellation_callback()

    # 1. PDF 분석 및 챕터별 분할
    print("📄 PDF 파일을 분석하고 챕터별로 분할하고 있습니다...\n")
    try:
        chapters_info = pdf_analysis_main(pdf_path)
    except Exception as e:
        print(f"[ERROR] PDF 분석 중 에러 발생: {type(e).__name__}: {str(e)}")
        traceback.print_exc()
        raise
    
    # 분석 후 취소 체크
    if cancellation_callback: cancellation_callback()

    print(f"총 {len(chapters_info)}개의 챕터를 발견했습니다.\n")
    for i, (title, path) in enumerate(chapters_info, 1):
        print(f"  {i}. {title}")
    print("\n")
    
    # 2. 모든 챕터에 대해 동시에 강의 에이전트 호출
    print("🎓 각 챕터에 대한 강의 설명을 생성하고 있습니다...\n")
    
    # 원래는 asyncio.run(run_all_lecture_agents(chapters_info)) 였으나
    # 취소 체크를 위해 여기서도 직접 핸들링하거나 run_all_lecture_agents 내부를 고쳐야 함.
    # 하지만 여기서는 간단히 실행 전후에만 체크.
    # 더 정교한 제어가 필요하면 run_all_lecture_agents 내부 루프에도 cancellation_callback을 넣어야 함.
    
    # 취소 체크
    if cancellation_callback: cancellation_callback()

    lecture_results = asyncio.run(run_all_lecture_agents(chapters_info))
    
    # 실행 후 취소 체크
    if cancellation_callback: cancellation_callback()

    print("모든 강의 설명 생성이 완료되었습니다.\n")
    print("="*60 + "\n")
    
    # 3. Q&A 처리 (skip_qa가 False인 경우에만)
    if not skip_qa:
        # 순서대로 강의 진행
        for i, ((chapter_title, pdf_path), lecture_dict) in enumerate(zip(chapters_info, lecture_results), 1):
            # 루프마다 취소 체크
            if cancellation_callback: cancellation_callback()

            print("\n" + "="*60)
            print(f"📚 Chapter {i}: {chapter_title}")
            print("="*60 + "\n")
            
            explanation = lecture_dict[chapter_title]
            
            # 설명문을 처리하면서 질문이 나오면 Q&A 진행
            process_explanation_with_qa(explanation, chapter_title, pdf_path)
            
            # 다음 챕터로 넘어가기 전 구분선
            if i < len(chapters_info):
                print("\n" + "="*60)
                print("다음 챕터로 이동합니다...")
                print("="*60 + "\n")
                time.sleep(1)
        
        # 4. 모든 강의 완료
        print("\n" + "="*60)
        print("모든 강의가 완료되었습니다!")
        print("="*60)
    
    # 결과 반환 (API 호출 시 사용)
    return chapters_info, lecture_results


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("사용법: python integration.py <PDF 파일 경로>")
        print("예시: python integration.py /Users/jhkim/Desktop/Edu_Agent/02-SW-Process.pdf")
        sys.exit(1)
    
    pdf_path = sys.argv[1]
    
    try:
        main(pdf_path)
    except FileNotFoundError as e:
        print(f"❌ 오류: 파일을 찾을 수 없습니다 - {e}")
        sys.exit(1)
    except Exception as e:
        print(f"❌ 오류가 발생했습니다: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)

