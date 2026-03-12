import os
import pathlib
import asyncio
import time
from typing import TypedDict, AsyncGenerator
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
    explanation: str


# 시스템 프롬프트: 페르소나 정의
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


def generate_explanation(state: LectureState) -> LectureState:
    """
    강의 자료(PDF/MD)를 읽어 교수님 페르소나로 강의 텍스트를 생성하는 함수
    """
    client = genai.Client(api_key=GEMINI_API_KEY)
    file_path = pathlib.Path(state["pdf_path"])

    # 1. 파일 형식에 따른 분기 처리 (Markdown 호환성 확보)
    file_extension = file_path.suffix.lower()

    if file_extension == ".pdf":
        # PDF: 바이너리로 읽어서 MIME 타입 지정
        content_part = types.Part.from_bytes(
            data=file_path.read_bytes(),
            mime_type="application/pdf",
        )
    elif file_extension == ".md":
        # Markdown: 텍스트로 읽어서 프롬프트에 직접 삽입
        try:
            text_content = file_path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            # 인코딩 이슈 대비
            text_content = file_path.read_text(encoding="cp949")

        content_part = f"""
[강의 자료 내용 ({file_extension})]
{text_content}
"""
    else:
        raise ValueError(f"지원하지 않는 파일 형식입니다: {file_extension}")

    # 2. 사용자 프롬프트 구성
    user_prompt = f"""[현재 챕터]: {state['chapter_title']}

위 챕터 주제를 중심으로 강의를 진행해주세요. 
전체 자료 중 해당 챕터와 관련된 부분을 중점적으로 설명하고, 학생들의 사고를 확장시키는 질문을 1~2개 포함해주세요.
"""

    # 3. API 호출
    response = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=[
            SYSTEM_PROMPT,
            content_part,
            user_prompt,
        ],
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
    file_path = pathlib.Path(state["pdf_path"])
    
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
    
    # 2. 파일 로드
    file_extension = file_path.suffix.lower()
    if file_extension == ".pdf":
        content_part = types.Part.from_bytes(
            data=file_path.read_bytes(),
            mime_type="application/pdf",
        )
        yield StreamingEvent(
            type="thought",
            delta="PDF 파일을 로드했습니다. 문서 구조를 분석 중...\n"
        )
    elif file_extension == ".md":
        try:
            text_content = file_path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            text_content = file_path.read_text(encoding="cp949")
        content_part = f"[강의 자료 내용 ({file_extension})]\n{text_content}"
        yield StreamingEvent(
            type="thought",
            delta="Markdown 파일을 로드했습니다. 내용을 분석 중...\n"
        )
    else:
        yield StreamingEvent(
            type="error",
            delta=f"지원하지 않는 파일 형식입니다: {file_extension}"
        )
        return
    
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
    
    # 3-1. 특정 주제에 대한 시각화 데이터 생성 예시
    # DQN, 강화학습, 마르코프 프로세스 등 특정 주제에 대해 시각화 생성
    chapter_title_lower = state['chapter_title'].lower()
    
    if "dqn" in chapter_title_lower or "deep q-network" in chapter_title_lower:
        # DQN 아키텍처 시각화
        dqn_viz = ThoughtNode.create(
            step_id="step_002_viz",
            content="DQN의 핵심인 Experience Replay와 Target Network의 관계를 시각화합니다.",
            status="processing",
            viz_type="flowchart",
            viz_data="""graph LR
  A[Experience] --> B(Replay Buffer)
  B --> C{Sampling}
  C --> D[Main Network]
  E[Target Network] -- Update --> D
  D --> F[Q-values]
  F --> G[Action Selection]"""
        )
        yield StreamingEvent(type="thought", content=dqn_viz)
        await asyncio.sleep(0.3)
        dqn_viz.status = "completed"
        yield StreamingEvent(type="thought", content=dqn_viz)
    
    elif "마르코프" in chapter_title_lower or "markov" in chapter_title_lower:
        # 마르코프 프로세스 시각화
        markov_viz = ThoughtNode.create(
            step_id="step_002_viz",
            content="마르코프 프로세스의 상태 전이를 시각화합니다.",
            status="processing",
            viz_type="graph",
            viz_data="""graph LR
  A[State 1] -->|P_12| B[State 2]
  B -->|P_23| C[State 3]
  C -->|P_31| A
  A -->|P_11| A
  B -->|P_22| B
  C -->|P_33| C"""
        )
        yield StreamingEvent(type="thought", content=markov_viz)
        await asyncio.sleep(0.3)
        markov_viz.status = "completed"
        yield StreamingEvent(type="thought", content=markov_viz)
    
    user_prompt = f"""[현재 챕터]: {state['chapter_title']}

위 챕터 주제를 중심으로 강의를 진행해주세요. 
전체 자료 중 해당 챕터와 관련된 부분을 중점적으로 설명하고, 학생들의 사고를 확장시키는 질문을 1~2개 포함해주세요.
"""
    
    # 4. Gemini API 스트리밍 호출 (thinking 활성화)
    try:
        # 스트리밍 응답 받기
        response_stream = await asyncio.to_thread(
            client.models.generate_content_stream,
            model="gemini-2.5-flash",
            contents=[
                SYSTEM_PROMPT,
                content_part,
                user_prompt,
            ],
            config=types.GenerateContentConfig(
                thinking_budget=10000  # thinking 토큰 예산 설정
            )
        )
        
        # 추론 과정 완료 표시
        yield StreamingEvent(
            type="thought",
            content=ThoughtNode(
                step_id="step_002",
                content=f"챕터 '{state['chapter_title']}'의 핵심 개념을 추출 중...",
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
                            # Gemini API의 thinking 출력은 특정 패턴을 가질 수 있음
                            # 실제 구현은 API 응답 구조에 따라 조정 필요
                            
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


def main(chapter_title: str, pdf_path: str) -> dict:
    """외부에서 호출하는 진입점"""
    initial_state: LectureState = {
        "chapter_title": chapter_title,
        "pdf_path": pdf_path,
        "explanation": "",
    }
    result_state = generate_explanation(initial_state)

    # 딕셔너리 형태로 반환 {챕터명: 설명}
    return {chapter_title: result_state["explanation"]}