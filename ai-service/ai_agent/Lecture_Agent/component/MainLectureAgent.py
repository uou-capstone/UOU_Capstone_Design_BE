import os
from dotenv import load_dotenv
from typing import TypedDict
from google import genai
from google.genai import types
import pathlib
from langgraph.graph import StateGraph, START, END

# API Key 설정 (.env에서 로드)
load_dotenv()
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")


class LectureState(TypedDict):
    chapter_title: str
    pdf_path: str
    explanation: str


SYSTEM_PROMPT = """# [Role]
당신은 학생들에게 강의를 진행하는, 친절하고 전문 지식을 갖춘 교수입니다. 당신의 목표는 학생들이 주어진 학습 자료를 수동적으로 받아 적는 것이 아니라, **스스로 생각하고 개념을 깨우칠 수 있도록 돕는 것**입니다.

# [Task]
당신은 '챕터 제목'과 해당 챕터의 '강의 자료 (PDF 파일)'를 입력받습니다.
당신의 임무는 이 자료를 바탕으로, 학생에게 직접 강의하듯이 핵심 내용을 설명하고, 학생의 사고를 자극하는 질문을 던지는 것입니다.

# [Input Format]
- [챕터 제목]: 강의 자료의 챕터 제목
- [강의 자료]: PDF 파일 전체 내용

# [Output Generation Rules]
1. **강의 톤**: 실제 강의실에서 학생들에게 말하듯이, 친절하고 이해하기 쉬운 구어체로 설명해야 합니다.

2. **내용 균형**: [강의 자료]로 입력된 내용 전체를 골고루 다루어야 합니다. 특정 페이지나 주제에만 치우치지 말고, 자료 전반의 핵심 개념과 흐름을 요약하고 설명해 주세요.

3. **사고 유도형 질문 생성**: 강의 설명 내용 중간 혹은 마지막에, 학생이 스스로 개념에 대해 고민해볼 수 있도록 유도하는 질문을 **최소 1개에서 최대 4개까지** 생성해야 합니다.
   * **질문의 목적**: 단순한 암기 확인이 아니라, **개념을 설명하기 전에 학생이 먼저 생각해볼 수 있도록 유도**하는 것이 중요합니다.
   * **질문 방식**: 배울 개념과 **비슷한 일상 속 예시**를 들어 질문하거나, 학생이 배운 내용을 **스스로 응용하여 답을 고민**해볼 수 있도록 질문을 설계해야 합니다.
   * **난이도**: 학생이 정답을 바로 맞히지 못하더라도, 고민하는 과정 자체로 학습이 될 수 있는 적절한 난이도여야 합니다.
   * **형식**: 질문은 **반드시** 다음 지정된 형식으로만 작성해야 합니다.
     ```
     [질문] (여기에 질문 내용을 작성하세요) [/질문]
     ```

4. **질문 생성 예외**:
   * 만약 입력된 [강의 자료]의 내용이 본격적인 학습 내용이 아니라, 단순한 **개요(Outline), 서론(Intro), 목차(Table of Contents), 참고 문헌, 또는 기타 무의미한 텍스트**로 판단될 경우, 강의 설명을 간략히 하거나 생략할 수 있으며, **이 경우에는 [질문]을 절대로 생성해서는 안 됩니다.**

# [Output]
학생들이 이해하기 쉬운 강의 설명과 함께, 사고를 유도하는 질문을 [질문][/질문] 형식으로 포함하여 작성해주세요.
"""


def generate_explanation(state: LectureState) -> LectureState:
    """PDF 파일을 읽어 강의 설명을 생성하는 함수"""
    client = genai.Client(api_key=GEMINI_API_KEY)
    filepath = pathlib.Path(state["pdf_path"])
    
    user_prompt = f"""[챕터 제목]: {state["chapter_title"]}

위의 챕터 제목과 함께 제공된 PDF 강의 자료를 바탕으로, 학생들에게 강의를 진행해주세요.
전체 내용을 골고루 다루면서, 핵심 개념을 이해하기 쉽게 설명하고, 학생들의 사고를 자극하는 질문을 포함해주세요.
"""
    
    response = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=[
            SYSTEM_PROMPT,
            types.Part.from_bytes(
                data=filepath.read_bytes(),
                mime_type='application/pdf',
            ),
            user_prompt
        ]
    )
    
    return {**state, "explanation": response.text}


def main(chapter_title: str, pdf_path: str) -> dict:
    """
    Main 함수: 챕터 제목과 PDF 경로를 받아 강의 설명을 생성
    
    Args:
        chapter_title (str): 챕터 제목
        pdf_path (str): PDF 파일 경로
    
    Returns:
        dict: {"챕터title": "챕터title에 맞는 설명문"} 형태의 딕셔너리
    """
    # StateGraph 워크플로우 구성
    workflow = StateGraph(LectureState)
    workflow.add_node("generate_explanation", generate_explanation)
    workflow.add_edge(START, "generate_explanation")
    workflow.add_edge("generate_explanation", END)
    lecture_agent = workflow.compile()
    
    # 초기 상태 설정
    initial_state = {
        "chapter_title": chapter_title,
        "pdf_path": pdf_path,
        "explanation": ""
    }
    
    # 워크플로우 실행
    final_state = lecture_agent.invoke(initial_state)
    
    # 결과를 딕셔너리 형태로 반환
    return {
        chapter_title: final_state["explanation"]
    }


# 테스트용 코드 (직접 실행하지 않는 이상 수행 X)
if __name__ == "__main__":
    result = main(
        chapter_title="DQN 핵심 개념",
        pdf_path=r"C:\\Users\\goril\\Desktop\\Capstone\\Capstone_BE\\ai-service\\uploads\\ch6_DQN_(Deep_Q_learning).pdf"
    )
    
    print(f"챕터 제목: {list(result.keys())[0]}")
    print(f"설명:\n{list(result.values())[0]}")

