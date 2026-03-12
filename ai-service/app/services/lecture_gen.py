from ai_agent.Lecture_Agent.component.MainLectureAgent import main as generate_script


def generate_markdown(chapter_title: str, pdf_path: str, md_path: str) -> str:
    """
    Lecture_Agent를 호출하여 강의 대본(설명)을 생성합니다.
    
    Args:
        chapter_title: 챕터 제목
        pdf_path: PDF 파일 경로
        md_path: MD 파일 경로
    
    Returns:
        str: 생성된 강의 대본 텍스트
    """
    # MainLectureAgent.main()은 {title: explanation} 딕셔너리를 반환함
    result = generate_script(chapter_title, pdf_path, md_path)
    
    # 딕셔너리에서 설명 텍스트만 추출
    if isinstance(result, dict):
        return result.get(chapter_title, "")
    return str(result)


