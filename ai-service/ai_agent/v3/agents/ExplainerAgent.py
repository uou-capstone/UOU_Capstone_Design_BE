"""
ExplainerAgent

설계서 §5.1: ExplainerAgent
- 입력: page_number, pdf_path, chapter_title(선택), detail(NORMAL|DETAILED)
- 출력: thought_delta / answer_delta / done (NDJSON 스트리밍)
- 피드백 반영: 챕터 단위 → 페이지 단위 설명으로 전환
  - Gemini에 PDF 전체 + 현재 페이지 번호 전달
  - "현재 페이지 위주로 설명" 조건 시스템 프롬프트에 명시
"""
from __future__ import annotations

from typing import AsyncGenerator, Optional

from ai_agent.bridge.GeminiBridgeClient import GeminiBridgeClient
from ai_agent.types.domain import NdjsonEvent, NdjsonEventType

TEXT_CONTEXT_PROMPT = """너는 MergeEduAgent의 페이지 설명 에이전트다.
현재 학생이 보고 있는 페이지는 {page_number}페이지다.
반드시 현재 페이지 번호를 문장에 자연스럽게 명시하고, 한국어 Markdown으로 설명하라.
현재 페이지 텍스트를 중심으로 설명하고, 이전/다음 페이지는 흐름 파악용 보조 참고로만 사용하라.
PDF 원문이 영어 또는 다른 언어여도 설명, 요약, 예시는 자연스러운 한국어로 풀어 써라.
전문 용어, 고유명사, 코드, 수식, API 이름은 필요한 경우 영어 원문을 괄호로 병기할 수 있다.
매 페이지마다 새 강의를 시작하는 것처럼 인사하지 마라.
답변 전체에서 "안녕하세요", "학생 여러분", "여러분", "지난 시간에 이어", "오늘은", "이번 시간에는" 같은 도입/호명 문구를 절대 쓰지 마라.
첫 문장은 반드시 `## 핵심 요지` 헤딩으로 시작하라.

{detail_instruction}

설명 전략:
- 현재 페이지가 장의 도입/목차/키워드 나열이면, 세부 내용을 과하게 지어내지 말고 "이 페이지는 앞으로 배울 개념의 지도"라는 관점으로 각 키워드가 어떤 역할을 하는지 연결하라.
- 현재 페이지가 개념 정의/공식/절차를 담고 있으면, 정의 → 왜 필요한지 → 작동 방식 → 짧은 예시 순서로 설명하라.
- 학생 수준이 BEGINNER 또는 초등학생에 가까우면 쉬운 일상 비유를 하나 포함하라.
- 학생 수준이 INTERMEDIATE이면 핵심 정의와 대표 예시, 헷갈리기 쉬운 비교 지점을 포함하라.
- 학생 수준이 ADVANCED이면 조건, 예외, 메커니즘을 더 명확히 설명하라.
- 이전 페이지와 다음 페이지는 "이 개념이 앞/뒤 흐름과 어떻게 이어지는지"를 짧게 연결하는 용도로만 사용하라.
- 같은 페이지를 다시 설명하는 상황에서도 같은 표현을 반복하지 않도록, 비유나 관점을 바꿔 설명하라.

포맷 규칙:
- 수식이 등장하면 반드시 LaTeX 문법을 사용하라. 예: `$x^2 + y^2 = r^2$`, `$$E = mc^2$$`
- 프로그래밍 코드, 터미널 명령어, 파일명, 키워드가 등장하면 반드시 코드 포맷을 사용하라. 예: `pip install fastapi`
- 중요한 개념, 용어, 정의, 조건은 **굵게** 표시하라.
- 짧은 문단과 글머리 기호를 사용해 읽기 쉽게 구성하라.
- 가능한 한 `## 핵심 요지` → `## 설명` → (선택) `### 예시/비유` → `## 오해 포인트` → `## 마무리` 흐름을 따르되, 내용이 적은 개요 페이지는 짧게 끝내라.

학생 수준: {learner_level}
학생 통합 메모리:
{learner_memory_digest}

현재 페이지 텍스트:
{page_text}

이전 페이지 참고:
{prev_text}

다음 페이지 참고:
{next_text}
"""

PDF_FALLBACK_PROMPT = """# [Role]
당신은 학생들에게 강의를 진행하는, 친절하고 전문 지식을 갖춘 튜터형 교수입니다.

# [Task]
학생이 현재 보고 있는 PDF 페이지를 기준으로 직관적이고 이해하기 쉽게 설명해주세요.
현재 페이지가 장의 도입/목차/키워드 나열이면 "앞으로 배울 개념의 지도"처럼 설명하고,
개념 페이지라면 정의, 필요성, 작동 방식, 예시를 순서대로 풀어주세요.

# [언어 규칙]
- 최종 설명은 반드시 자연스러운 한국어로 작성하세요.
- PDF 원문이 영어 또는 다른 언어여도 설명, 요약, 예시는 한국어로 풀어 쓰세요.
- 전문 용어, 고유명사, 코드, 수식, API 이름은 필요한 경우 영어 원문을 괄호로 병기할 수 있습니다.
- 제목과 소제목도 한국어로 작성하세요.
- 원문 문장을 길게 영어로 그대로 옮기지 말고, 학습자가 이해하기 쉬운 한국어 설명으로 바꾸세요.

# [출력 형식 (가독성 및 포맷팅: 매우 중요)]
- 출력은 **마크다운**으로 작성하세요.
- **구조화(헤딩)**: 내용의 흐름이 한눈에 보이도록 `##` 또는 `###` 기호로 소제목을 달아주세요.
  - 예: `## 핵심 요지`, `## 설명`, `### 예시`, `## 오해 포인트`, `## 마무리`
- **수식 및 코드 (필수 규칙)**:
  - 수식이 등장하면 반드시 **LaTeX 문법**을 사용하세요.
    - **인라인 수식** 예: `$x^2 + y^2 = r^2$`
    - **블록 수식** 예: `$$E = mc^2$$`
  - 프로그래밍 코드/터미널 명령어/파일명/키워드가 등장하면 반드시 **코드 포맷**을 사용하세요.
    - **인라인 코드** 예: `` `pip install fastapi` ``, `` `ai_agent/v3/agents/ExplainerAgent.py` ``
    - **코드 블록** 예:
      ```python
      def f(x):
          return x**2
      ```
- 반드시 **짧은 문단(2~4문장)** 단위로 줄바꿈을 넣어, 텍스트가 뭉치지 않게 하세요.
- 나열이 필요한 경우에는 문장으로 길게 이어 쓰지 말고 **글머리 기호(-)** 로 정리하세요.
- 중요한 개념/용어/정의/조건은 **굵게(Bold)** 처리해 눈에 띄게 하세요. (예: **핵심 용어**, **주의**, **시험 포인트**)
- 가능한 한 아래 구조를 따르세요(페이지 성격에 맞게 생략/축약 가능):
  - `## 핵심 요지` → `## 설명` → (선택) `### 예시/비유/코드/수식` → `## 오해 포인트` → `## 마무리`

# [핵심 규칙]
1. **현재 페이지 집중**: 반드시 지정된 현재 페이지의 내용을 중심으로 설명하세요.
   전체 PDF를 두루뭉술하게 요약하거나, 다른 페이지 내용을 주제로 삼으면 안 됩니다.
2. **강의 톤**: 실제 강의실에서 학생에게 말하듯 친절하고 이해하기 쉬운 구어체로 설명하세요.
3. **튜터링**: 학생 수준이 낮으면 쉬운 일상 비유를 하나 포함하고, 중급 이상이면 정의와 작동 원리, 헷갈리기 쉬운 비교 지점을 포함하세요.
4. **반복 금지**: 한 번 설명한 개념을 똑같이 다시 반복하지 마세요.
5. **도입 반복 금지**: 이 설명은 이미 진행 중인 학습 세션에서 페이지를 넘길 때마다 호출됩니다.
   매 페이지마다 새 강의를 시작하는 것처럼 인사하지 마세요.
   답변 전체에서 "안녕하세요", "학생 여러분", "여러분", "지난 시간에 이어", "오늘은", "이번 시간에는" 같은 도입/호명 문구를 절대 쓰지 마세요.
   학생을 부를 필요가 있으면 호명하지 말고 바로 개념 설명으로 들어가세요.
   첫 문장은 반드시 `## 핵심 요지` 헤딩으로 시작하세요.
"""

DETAILED_SUFFIX = """
# [Detail Mode: DETAILED]
이 페이지는 학습자가 이전에 낮은 점수를 받거나 약점으로 표시된 내용입니다.
- 개념을 더욱 깊이 있고 자세하게 설명해주세요.
- 구체적인 예시와 비유를 적극 활용해주세요.
- 핵심 용어를 명확히 정의해주세요.
"""


def _detail_instruction(detail: str) -> str:
    if detail == "DETAILED":
        return (
            "- 최근 퀴즈 성과가 낮을 수 있으니 설명을 더 자세히 제공하라.\n"
            "- 정의, 직관, 예시, 오개념 교정 포인트를 구조화해 설명하라."
        )
    return "- 설명 깊이는 보통 수준으로 유지하고 핵심 개념을 짧고 명확하게 설명하라."


def _learner_memory_text(value: Optional[str]) -> str:
    return value.strip() if value and value.strip() else "(개인화 메모리 없음)"


class ExplainerAgent:
    """
    페이지 단위 설명을 스트리밍으로 생성하는 에이전트.
    ToolDispatcher 에서 EXPLAIN_PAGE 툴 호출 시 사용.
    """

    def __init__(self, bridge: GeminiBridgeClient):
        self._bridge = bridge

    async def run_stream(
        self,
        page_number: int,
        pdf_path: str,
        chapter_title: Optional[str] = None,
        detail: str = "NORMAL",
        page_text: Optional[str] = None,
        prev_text: str = "",
        next_text: str = "",
        learner_level: str = "INTERMEDIATE",
        learner_memory_digest: Optional[str] = None,
    ) -> AsyncGenerator[NdjsonEvent, None]:
        """
        페이지 단위 강의 설명 스트리밍 생성.

        Args:
            page_number: 현재 사용자가 보고 있는 페이지 번호 (1-indexed)
            pdf_path: PDF 파일 경로
            chapter_title: 챕터 제목 (선택, 없으면 페이지 번호로 대체)
            detail: "NORMAL" | "DETAILED" (복습 모드)
        """
        if page_text and page_text.strip():
            prompt = TEXT_CONTEXT_PROMPT.format(
                page_number=page_number,
                detail_instruction=_detail_instruction(detail),
                learner_level=learner_level or "INTERMEDIATE",
                learner_memory_digest=_learner_memory_text(learner_memory_digest),
                page_text=page_text.strip(),
                prev_text=(prev_text or "").strip() or "(없음)",
                next_text=(next_text or "").strip() or "(없음)",
            )
            async for event in self._bridge.stream([prompt], agent="explainer", tool="EXPLAIN_PAGE"):
                yield event
            return

        system = PDF_FALLBACK_PROMPT + (DETAILED_SUFFIX if detail == "DETAILED" else "")

        pdf_part = await self._bridge.load_pdf_part(pdf_path)

        label = chapter_title or f"페이지 {page_number}"
        user_prompt = (
            f"[현재 페이지 번호]: {page_number}\n"
            f"[챕터/제목 참고]: {label}\n\n"
            f"현재 학생은 PDF의 {page_number}페이지를 보고 있습니다.\n"
            "이 페이지의 내용을 중심으로 강의를 진행해주세요.\n"
            "반드시 한국어로 설명하고, 영어 원문은 필요한 전문 용어만 괄호로 병기하세요.\n"
            "다른 페이지 내용으로 벗어나지 말고, 이 페이지에 집중해서 설명하세요.\n"
        )

        contents = [system, pdf_part, user_prompt]

        async for event in self._bridge.stream(contents, agent="explainer", tool="EXPLAIN_PAGE"):
            yield event

    async def run(
        self,
        page_number: int,
        pdf_path: str,
        chapter_title: Optional[str] = None,
        detail: str = "NORMAL",
        page_text: Optional[str] = None,
        prev_text: str = "",
        next_text: str = "",
        learner_level: str = "INTERMEDIATE",
        learner_memory_digest: Optional[str] = None,
    ) -> str:
        """비스트리밍 버전: 전체 설명 텍스트 반환"""
        if page_text and page_text.strip():
            prompt = TEXT_CONTEXT_PROMPT.format(
                page_number=page_number,
                detail_instruction=_detail_instruction(detail),
                learner_level=learner_level or "INTERMEDIATE",
                learner_memory_digest=_learner_memory_text(learner_memory_digest),
                page_text=page_text.strip(),
                prev_text=(prev_text or "").strip() or "(없음)",
                next_text=(next_text or "").strip() or "(없음)",
            )
            return await self._bridge.generate([prompt])

        system = PDF_FALLBACK_PROMPT + (DETAILED_SUFFIX if detail == "DETAILED" else "")

        pdf_part = await self._bridge.load_pdf_part(pdf_path)

        label = chapter_title or f"페이지 {page_number}"
        user_prompt = (
            f"[현재 페이지 번호]: {page_number}\n"
            f"[챕터/제목 참고]: {label}\n\n"
            f"현재 학생은 PDF의 {page_number}페이지를 보고 있습니다.\n"
            "이 페이지의 내용을 중심으로 강의를 진행해주세요.\n"
            "반드시 한국어로 설명하고, 영어 원문은 필요한 전문 용어만 괄호로 병기하세요.\n"
        )

        contents = [system, pdf_part, user_prompt]

        return await self._bridge.generate(contents)
