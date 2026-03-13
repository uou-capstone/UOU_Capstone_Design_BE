import os
import pathlib
import asyncio
import time
from typing import TypedDict, AsyncGenerator, Optional
from dotenv import load_dotenv
from google import genai
from google.genai import types

# 환경 변수 로드
load_dotenv()
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")

# 스트리밍 스키마 임포트
try:
    from ai_agent.common import StreamingEvent, ThoughtNode
except ImportError:
    # Fallback: 직접 정의
    from pydantic import BaseModel
    from typing import Literal, Optional, Dict, Any
    
    class ThoughtNode(BaseModel):
        step_id: str
        content: str
        status: Literal["processing", "completed", "failed"]
        visual_type: Optional[Literal["flowchart", "math_block", "graph"]] = None
        timestamp: float = time.time()
    
    class StreamingEvent(BaseModel):
        type: Literal["thought", "answer", "error"]
        delta: Optional[str] = None
        content: Optional[ThoughtNode] = None
        metadata: Optional[Dict[str, Any]] = None


class LectureState(TypedDict):
    chapter_title: str
    pdf_path: str
    md_path: Optional[str]
    explanation: str


# 시스템 프롬프트: 페르소나 정의
SYSTEM_PROMPT = """# [Role]
당신은 학생들에게 강의를 진행하는, 친절하고 전문 지식을 갖춘 교수입니다. 당신의 목표는 학생들이 주어진 학습 자료를 수동적으로 받아 적는 것이 아니라, **스스로 생각하고 개념을 깨우칠 수 있도록 돕는 것**입니다.

# [Task]
당신은 '챕터 제목'과 해당 챕터의 두 가지 자료를 입력받습니다.
- 원본 시각 자료: PDF
- 강의 대본 초안: Markdown(MD)
당신의 임무는 **제공된 강의 대본 초안(MD)의 텍스트와 원본 시각 자료(PDF)를 모두 종합**하여,
학생에게 직접 강의하듯이 핵심 내용을 설명하고, 학생의 사고를 자극하는 질문을 던지는 것입니다.

# [Input Format]
- [챕터 제목]: 강의 자료의 챕터 제목
- [원본 시각 자료]: PDF (해당 챕터에 해당하는 분할본)
- [강의 대본 초안]: MD 텍스트 (해당 챕터에 해당하는 분할본)

# [Output Generation Rules]
1. **강의 톤**: 실제 강의실에서 학생들에게 말하듯이, 친절하고 이해하기 쉬운 구어체로 설명해야 합니다.

2. **내용 균형**: [강의 자료]로 입력된 내용 전체를 골고루 다루어야 합니다. 특정 페이지나 주제에만 치우치지 말고, 자료 전반의 핵심 개념과 흐름을 요약하고 설명해 주세요.

3. **순차적 흐름과 반복 절대 금지 (CRITICAL)**:
   - 강의 자료의 논리적 흐름에 따라 순서대로 설명해야 합니다.
   - **한 번 설명한 개념이나 문단을 뒤에서 똑같이 다시 반복해서 출력하는 것은 절대 금지합니다.** 문맥이 자연스럽게 이어지도록 전진만 하세요.

4. **사고 유도형 질문 생성 및 템플릿**: 강의 설명 내용 중간 혹은 마지막에, 학생이 스스로 개념에 대해 고민해볼 수 있도록 유도하는 질문을 **최소 1개에서 최대 4개까지** 생성해야 합니다.
   * **질문의 목적**: 단순한 암기 확인이 아니라, '응용', '비교', '일상 사례 적용' 위주로 출제하여 학생이 스스로 고민해볼 수 있도록 유도합니다.
   * **형식 (엄수)**: 질문은 **반드시** `[질문]` 태그로 시작하고 `[/질문]` 태그로 끝나야 합니다. (마크다운 코드 블록이나 다른 특수기호를 태그에 섞지 마세요)
   * **출력 템플릿 예시**: 아래와 같은 자연스러운 흐름과 태그 방식을 엄격히 지켜주세요.
     --- [출력 템플릿 예시 시작] ---
     오늘 배울 첫 번째 핵심 주제는 ~입니다. 슬라이드를 보면 이 개념은 쉽게 말해 ~와 같습니다.
     (해당 개념 설명 진행...)

     [질문]
     방금 배운 내용을 바탕으로, 만약 이런 일상생활 상황이 발생한다면 어떻게 적용해볼 수 있을까요?
     [/질문]

     자, 이어서 다음 내용을 살펴볼까요? 방금 배운 개념과 연결되는 ~에 대해 알아보겠습니다.
     (다음 개념 설명 진행...)

     [질문]
     A방식과 B방식의 가장 큰 차이점은 무엇일까요?
     [/질문]

     마지막으로 ~을 정리하며 마치겠습니다.
     --- [출력 템플릿 예시 끝] ---

5. **질문 생성 예외**:
   * 만약 입력된 [강의 자료]의 내용이 본격적인 학습 내용이 아니라, 단순한 **개요(Outline), 서론(Intro), 목차(Table of Contents), 참고 문헌, 또는 기타 무의미한 텍스트**로 판단될 경우, 강의 설명을 간략히 하거나 생략할 수 있으며, **이 경우에는 [질문]을 절대로 생성해서는 안 됩니다.**

# [Output]
학생들이 이해하기 쉬운 강의 설명과 함께, 사고를 유도하는 질문을 [질문][/질문] 형식으로 포함하여 작성해주세요.
"""


def _read_text_any_encoding(path: pathlib.Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return path.read_text(encoding="cp949")


def generate_explanation(state: LectureState) -> LectureState:
    """
    강의 자료(PDF/MD)를 읽어 교수님 페르소나로 강의 텍스트를 생성하는 함수
    """
    client = genai.Client(api_key=GEMINI_API_KEY)
    pdf_path = pathlib.Path(state["pdf_path"])
    md_path_str = state.get("md_path")
    md_path = pathlib.Path(md_path_str) if md_path_str else None

    # 1. 챕터별 PDF(바이너리) + MD(텍스트) 동시 로드 (Two-track)
    if pdf_path.suffix.lower() != ".pdf":
        raise ValueError(f"pdf_path는 .pdf여야 합니다: {pdf_path}")

    pdf_part = types.Part.from_bytes(
        data=pdf_path.read_bytes(),
        mime_type="application/pdf",
    )

    md_part = None
    if md_path:
        if md_path.suffix.lower() != ".md":
            raise ValueError(f"md_path는 .md여야 합니다: {md_path}")
        md_text = _read_text_any_encoding(md_path)
        md_part = f"[강의 대본 초안 (MD) - 해당 챕터]\n{md_text}"

    # 2. 사용자 프롬프트 구성
    user_prompt = f"""[현재 챕터]: {state['chapter_title']}

제공된 강의 대본 초안(MD)의 텍스트와 원본 시각 자료(PDF)를 모두 종합하여 강의를 진행해주세요.
위 챕터 주제를 중심으로, 해당 챕터 자료 범위 안에서만 근거를 사용해 설명하세요.
학생들의 사고를 확장시키는 질문을 1~2개 포함해주세요.
"""

    # 3. API 호출
    contents = [SYSTEM_PROMPT, pdf_part]
    if md_part:
        contents.append(md_part)
    contents.append(user_prompt)

    response = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=contents,
    )

    return {**state, "explanation": response.text}


async def generate_explanation_streaming(
    state: LectureState
) -> AsyncGenerator[StreamingEvent, None]:
    """
    강의 설명을 생성하면서 추론 과정을 스트리밍합니다.
    Gemini API의 thinking 기능을 활용하여 내부 추론 과정을 실시간으로 전송합니다.
    """
    client = genai.Client(api_key=GEMINI_API_KEY)
    pdf_path = pathlib.Path(state["pdf_path"])
    md_path_str = state.get("md_path")
    md_path = pathlib.Path(md_path_str) if md_path_str else None
    
    # 1. 초기 생각 시작
    yield StreamingEvent(
        type="thought",
        content=ThoughtNode(
            step_id="step_001",
            content="강의 자료를 분석하고 챕터 주제를 파악하는 중...",
            status="processing",
            timestamp=time.time()
        )
    )
    
    # 2. 챕터별 PDF(바이너리) + MD(텍스트) 동시 로드
    if pdf_path.suffix.lower() != ".pdf":
        yield StreamingEvent(type="error", delta=f"pdf_path는 .pdf여야 합니다: {pdf_path}")
        return

    pdf_part = types.Part.from_bytes(
        data=pdf_path.read_bytes(),
        mime_type="application/pdf",
    )
    yield StreamingEvent(type="thought", delta="PDF(시각 자료) 챕터 파일을 로드했습니다.\n")

    md_part = None
    if md_path:
        if md_path.suffix.lower() != ".md":
            yield StreamingEvent(type="error", delta=f"md_path는 .md여야 합니다: {md_path}")
            return
        md_text = _read_text_any_encoding(md_path)
        md_part = f"[강의 대본 초안 (MD) - 해당 챕터]\n{md_text}"
        yield StreamingEvent(type="thought", delta="MD(대본 초안) 챕터 파일을 로드했습니다.\n")
    
    # 3. 챕터 분석
    yield StreamingEvent(
        type="thought",
        content=ThoughtNode(
            step_id="step_002",
            content=f"챕터 '{state['chapter_title']}'의 핵심 개념을 추출 중...",
            status="processing",
            timestamp=time.time()
        )
    )
    
    # [수정됨] 특정 과목(DQN, 마르코프)에 종속되었던 하드코딩된 시각화 다이어그램 주입 로직 제거

    user_prompt = f"""[현재 챕터]: {state['chapter_title']}

제공된 강의 대본 초안(MD)의 텍스트와 원본 시각 자료(PDF)를 모두 종합하여 강의를 진행해주세요.
위 챕터 주제를 중심으로, 해당 챕터 자료 범위 안에서만 근거를 사용해 설명하세요.
학생들의 사고를 확장시키는 질문을 1~2개 포함해주세요.
"""
    
    # 4. Gemini API 스트리밍 호출 (thinking 활성화)
    try:
        # 스트리밍 응답 받기
        response_stream = await asyncio.to_thread(
            client.models.generate_content_stream,
            model="gemini-2.5-flash",
            contents=[p for p in [SYSTEM_PROMPT, pdf_part, md_part, user_prompt] if p is not None],
            config=types.GenerateContentConfig(
                thinking_budget=10000  # thinking 토큰 예산 설정
            )
        )
        
        # 추론 과정 완료 표시
        yield StreamingEvent(
            type="thought",
            content=ThoughtNode(
                step_id="step_002",
                content=f"챕터 '{state['chapter_title']}'의 핵심 개념을 추출 완료했습니다.",
                status="completed",
                timestamp=time.time()
            )
        )
        
        # 5. 스트리밍 처리
        answer_buffer = ""
        thinking_buffer = ""
        
        async for chunk in response_stream:
            if hasattr(chunk, 'candidates') and chunk.candidates:
                candidate = chunk.candidates[0]
                if hasattr(candidate, 'content') and candidate.content:
                    for part in candidate.content.parts:
                        if hasattr(part, 'text') and part.text:
                            text = part.text
                            
                            # Thinking vs Answer 구분
                            # 간단한 휴리스틱: "생각", "추론", "분석" 등의 키워드가 있으면 thinking
                            thinking_keywords = ["생각", "추론", "분석", "검토", "확인", "고려"]
                            is_thinking = any(keyword in text for keyword in thinking_keywords)
                            
                            if is_thinking:
                                thinking_buffer += text
                                yield StreamingEvent(
                                    type="thought",
                                    delta=text
                                )
                            else:
                                answer_buffer += text
                                yield StreamingEvent(
                                    type="answer",
                                    delta=text
                                )
        
        # 최종 완료 표시
        yield StreamingEvent(
            type="thought",
            content=ThoughtNode(
                step_id="step_003",
                content="강의 설명 생성이 완료되었습니다.",
                status="completed",
                timestamp=time.time()
            )
        )
        
    except Exception as e:
        yield StreamingEvent(
            type="error",
            delta=f"에러 발생: {str(e)}"
        )


def main(chapter_title: str, pdf_path: str, md_path: Optional[str] = None) -> dict:
    """외부에서 호출하는 진입점"""
    initial_state: LectureState = {
        "chapter_title": chapter_title,
        "pdf_path": pdf_path,
        "md_path": md_path,
        "explanation": "",
    }
    result_state = generate_explanation(initial_state)

    # 딕셔너리 형태로 반환 {챕터명: 설명}
    return {chapter_title: result_state["explanation"]}