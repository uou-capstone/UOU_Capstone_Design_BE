from ai_agent.Lecture_Agent.component.MainQandAAgent import main as run_qa_agent


def evaluate_answer(original_q: str, user_answer: str, pdf_path: str) -> str:
    """
    MainQandAAgent를 호출하여 사용자 답변을 평가하고 JSON 문자열을 반환합니다.

    Args:
        original_q: 원본 질문
        user_answer: 사용자 답변
        pdf_path: PDF 파일 경로

    Returns:
        str: 평가 결과 JSON 문자열
    """
    qa_input = [(original_q, user_answer), pdf_path]
    return run_qa_agent(qa_input)
