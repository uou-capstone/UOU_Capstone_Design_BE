import os
import asyncio
import json
import pathlib
from typing import List, Optional

from dotenv import load_dotenv
from google import genai
from google.genai import types
from pydantic import BaseModel


load_dotenv()
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")


class RemedialStep(BaseModel):
    concept: str
    explanation: str
    question: str


class BadModeResponse(BaseModel):
    status: str
    steps: List[RemedialStep]


# 모범 답안 생성 시스템 프롬프트
MODEL_ANSWER_PROMPT = """### 롤 (Role)
당신은 고도로 전문화된 **학술 모범 답안 생성기(Academic Model Answer Generator)** 입니다.

### 임무 (Mission)
제공된 **[PDF]**(강의 자료 전체)만을 신뢰 근거로 하여 **[Original Question]**에 대한 **완결된 모범 답안**을 작성합니다. 답변은 채점 기준으로 바로 사용 가능한 정확·정밀 수준이어야 합니다.

### 출력 규격 (Output Constraints — 반드시 준수)
* **오직 정답 본문만 출력**합니다.
* **금지**: 제목, 섹션, 레이블("Answer:"), 마크다운 문법(`#`, `*`, `-`, `>`, 코드펜스 ```), 표, 링크, 인용, 페이지 표기, 각주, 출처 표기.
* 다중 문항은 **(a), (b), (c)** 처럼 괄호 번호로 구분(하이픈·번호목록 금지).
* 수식은 평문으로 표기(e.g., `y = mx + b`, `O(n log n)`), LaTeX 금지.
* 질문이 코드 산출물을 요구하면 **코드만** 평문으로 출력(설명 금지, 코드펜스 금지).
* **질문과 동일한 언어**로 답변합니다.
"""


# 상태 결정 시스템 프롬프트
VALIDATION_PROMPT = """### 롤 (Role)
당신은 고도로 전문화된 **학습자 답변 검증기(Learner Answer Validator)** 입니다.

### 임무 (Mission)
주어진 **[모범답안]**, **[PDF 전체 또는 스니펫]**, **[사용자 답변]**, **[원래 질문]**을 바탕으로, 사용자의 답변이 질문의 취지와 PDF 근거, 모범답안의 핵심 주장과 **실질적으로 합치**하는지 판정하여 **오직 `GOOD` 또는 `BAD`**를 출력합니다.

### 핵심 규칙
* 결과는 **대문자 영문 한 단어**로만 출력: `GOOD` 또는 `BAD`.
* **추가 설명, 마크업, 공백, 기호 금지.**
"""


# 보충 설명 시스템 프롬프트 (GOOD 모드)
SUPPLEMENTARY_GOOD_PROMPT = """### 롤 (Role)
당신은 고도로 전문화된 **학습 강화 설명 생성기(Learning Reinforcement Explainer)** 입니다.

### 임무 (Mission)
제공된 **[PDF]**, **[Original Question]**, **[Model Answer]**를 바탕으로, 이미 정답에 도달한 학습자가 **핵심 개념을 확실히 자기 것으로 만들도록** 돕는 **보충 설명문**을 작성합니다.

### 출력 규격
* **보충 설명문 1개**만 출력합니다.
"""


# BAD 모드 일괄 생성 시스템 프롬프트
REMEDIAL_BATCH_PROMPT = """### 롤 (Role)
당신은 오답을 낸 학습자를 위한 **하위 개념 학습 설계기**입니다.

### 임무 (Mission)
사용자가 오답을 제출했습니다. 제공된 강의 자료를 바탕으로 원본 질문과 관련된 핵심 개념을 파악하고, 이를 이해하기 위한 1~3개의 하위 개념(선수 개념 -> 후속 개념)으로 쪼개주세요.
각 하위 개념마다 명확한 해설(explanation)과 학습자가 이해했는지 확인하는 진단용 꼬리 질문(question) 1개씩을 생성하여 배열로 반환하세요.

### 입력 (Inputs)
1. **[Original Question]**: 원래 질문
2. **[Model Answer]**: 모범 답안
3. **[User Answer]**: 사용자의 오답

### 핵심 규칙
1. **오직 JSON 형식**으로만 출력해야 합니다.
2. `status`는 반드시 "BAD"로 설정하세요.
3. `steps` 배열 안에는 1개에서 최대 3개의 하위 개념 학습 단계가 포함되어야 합니다.
4. 질문(question)은 단답형이나 짧은 문장으로 답할 수 있도록 설계하세요.
"""


# ----------------------------
# Async Q&A (Prefetch-ready)
# ----------------------------
def _load_material_part(pdf_path: str):
    """
    강의 자료 로드 유틸.
    - PDF: types.Part(application/pdf)
    - .md/.txt: 텍스트(str)
    """
    filepath = pathlib.Path(pdf_path)
    ext = filepath.suffix.lower()

    if ext == ".pdf":
        return types.Part.from_bytes(
            data=filepath.read_bytes(),
            mime_type="application/pdf",
        )
    if ext in [".md", ".txt"]:
        return filepath.read_text(encoding="utf-8")

    raise ValueError(f"지원하지 않는 파일 형식입니다: {ext}")


async def call_gemini_async(contents, response_schema: Optional[type[BaseModel]] = None):
    """
    Gemini API 호출을 asyncio-friendly 하게 감싼 래퍼.
    response_schema가 전달되면 Structured Output(JSON) 모드로 동작합니다.
    """
    client = genai.Client(api_key=GEMINI_API_KEY)
    config = None

    if response_schema is not None:
        config = types.GenerateContentConfig(
            response_mime_type="application/json",
            response_schema=response_schema,
        )

    return await asyncio.to_thread(
        client.models.generate_content,
        model="gemini-2.5-flash",
        contents=contents,
        config=config,
    )


async def generate_model_answer_async(question: str, pdf_path: str) -> str:
    """
    Prefetch용: 모범 답안만 먼저 생성.
    """
    content_part = _load_material_part(pdf_path)
    user_prompt = f"[Original Question]: {question}\n\n위의 질문에 대한 모범 답안을 생성해주세요."
    response = await call_gemini_async([MODEL_ANSWER_PROMPT, content_part, user_prompt])
    return response.text


async def run_qa_flow_async(
    question: str,
    user_answer: str,
    pdf_path: str,
    precomputed_model_answer: Optional[str] = None,
) -> str:
    """
    채점 및 해설 생성 플로우.
    - GOOD: 보충 설명만 포함된 {"status": "GOOD", "explanation": "..."} JSON 문자열 반환
    - BAD: 하위 개념 학습 단계가 포함된 BadModeResponse 스키마 JSON 문자열 반환
    """
    content_part = _load_material_part(pdf_path)

    # 1) Model Answer
    model_answer = precomputed_model_answer or await generate_model_answer_async(
        question, pdf_path
    )

    # 2) Validation (GOOD/BAD)
    val_prompt = f"""[Original Question]: {question}
[Model Answer]: {model_answer}
[User Answer]: {user_answer}

위의 정보를 바탕으로 사용자의 이해도를 판정해주세요."""

    val_res = await call_gemini_async([VALIDATION_PROMPT, content_part, val_prompt])
    validation_result = (val_res.text or "").strip().upper()

    # 3) Branch
    if "GOOD" in validation_result:
        supp_prompt = f"""[Original Question]: {question}
[Model Answer]: {model_answer}

위의 정보를 바탕으로 학습자의 이해를 강화하는 보충 설명문을 작성해주세요."""
        final_res = await call_gemini_async(
            [SUPPLEMENTARY_GOOD_PROMPT, content_part, supp_prompt]
        )

        return json.dumps(
            {
                "status": "GOOD",
                "explanation": final_res.text,
            },
            ensure_ascii=False,
        )

    # BAD: 단일 API 호출로 하위 개념 학습 단계 일괄 생성
    bad_prompt = f"""[Original Question]: {question}
[User Answer]: {user_answer}
[Model Answer]: {model_answer}"""

    final_res = await call_gemini_async(
        contents=[REMEDIAL_BATCH_PROMPT, content_part, bad_prompt],
        response_schema=BadModeResponse,
    )

    # Structured Output(JSON) 문자열 그대로 프론트로 전달
    return final_res.text


def main(qa_input: list) -> str:
    """
    Main 함수: 질문, 사용자 답변, PDF 경로를 받아 보충 설명 또는 BAD 모드 플랜을 생성
    입력 형식: [(원래 질문, 사용자 답변), pdf경로]
    """
    (original_question, user_answer), pdf_path = qa_input
    return asyncio.run(run_qa_flow_async(original_question, user_answer, pdf_path))


if __name__ == "__main__":
    # 간단 수동 테스트
    print("=== GOOD/BAD 플로우 테스트 ===")
    sample = main([
        ("소프트웨어 프로세스란 무엇인가?", "그냥 코딩하는 거 아닌가요?"),
        "/path/to/sample.pdf",
    ])
    print(sample)

