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

SYSTEM_PROMPT = """# [Role]
당신은 학생들에게 강의를 진행하는, 친절하고 전문 지식을 갖춘 교수입니다.

# [Task]
학생이 현재 보고 있는 PDF 페이지를 기준으로 직관적이고 이해하기 쉽게 설명해주세요.

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
3. **반복 금지**: 한 번 설명한 개념을 똑같이 다시 반복하지 마세요.
4. **도입 반복 금지**: 이 설명은 이미 진행 중인 학습 세션에서 페이지를 넘길 때마다 호출됩니다.
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
    ) -> AsyncGenerator[NdjsonEvent, None]:
        """
        페이지 단위 강의 설명 스트리밍 생성.

        Args:
            page_number: 현재 사용자가 보고 있는 페이지 번호 (1-indexed)
            pdf_path: PDF 파일 경로
            chapter_title: 챕터 제목 (선택, 없으면 페이지 번호로 대체)
            detail: "NORMAL" | "DETAILED" (복습 모드)
        """
        system = SYSTEM_PROMPT + (DETAILED_SUFFIX if detail == "DETAILED" else "")

        pdf_part = await self._bridge.load_pdf_part(pdf_path)

        label = chapter_title or f"페이지 {page_number}"
        user_prompt = (
            f"[현재 페이지 번호]: {page_number}\n"
            f"[챕터/제목 참고]: {label}\n\n"
            f"현재 학생은 PDF의 {page_number}페이지를 보고 있습니다.\n"
            "이 페이지의 내용을 중심으로 강의를 진행해주세요.\n"
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
    ) -> str:
        """비스트리밍 버전: 전체 설명 텍스트 반환"""
        system = SYSTEM_PROMPT + (DETAILED_SUFFIX if detail == "DETAILED" else "")

        pdf_part = await self._bridge.load_pdf_part(pdf_path)

        label = chapter_title or f"페이지 {page_number}"
        user_prompt = (
            f"[현재 페이지 번호]: {page_number}\n"
            f"[챕터/제목 참고]: {label}\n\n"
            f"현재 학생은 PDF의 {page_number}페이지를 보고 있습니다.\n"
            "이 페이지의 내용을 중심으로 강의를 진행해주세요.\n"
        )

        contents = [system, pdf_part, user_prompt]

        return await self._bridge.generate(contents)
