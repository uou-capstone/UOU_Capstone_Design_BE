from ai_agent import MainQandAAgent


def evaluate_answer(original_q: str, user_answer: str, pdf_path: str) -> str:
    # MainQandAAgent.main은 입력 형식: [(원래 질문, 사용자 답변), pdf경로]
    qa_input = [(original_q, user_answer), pdf_path]
    supplementary_explanation = MainQandAAgent.main(qa_input)
    return supplementary_explanation


