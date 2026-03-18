"""
ExplainerAgent

설계서 §5.1: ExplainerAgent
- 입력: chapter_title, pdf_path, md_path(선택), detail(NORMAL|DETAILED)
- 출력: thought_delta / answer_delta / done (NDJSON 스트리밍)
- 기존 MainLectureAgent의 로직을 GeminiBridgeClient 스트리밍으로 래핑
"""
from __future__ import annotations

from typing import AsyncGenerator, Optional

from ai_agent.bridge.GeminiBridgeClient import GeminiBridgeClient
from ai_agent.types.domain import NdjsonEvent, NdjsonEventType

SYSTEM_PROMPT = """# [Role]
당신은 학생들에게 강의를 진행하는, 친절하고 전문 지식을 갖춘 교수입니다. 당신의 목표는 학생들이 주어진 학습 자료를 수동적으로 받아 적는 것이 아니라, **스스로 생각하고 개념을 깨우칠 수 있도록 돕는 것**입니다.

# [Task]
당신은 '챕터 제목'과 해당 챕터의 두 가지 자료를 입력받습니다.
- 원본 시각 자료: PDF
- 강의 대본 초안: Markdown(MD)
당신의 임무는 **제공된 강의 대본 초안(MD)의 텍스트와 원본 시각 자료(PDF)를 모두 종합**하여,
학생에게 직접 강의하듯이 핵심 내용을 설명하고, 학생의 사고를 자극하는 질문을 던지는 것입니다.

# [Output Generation Rules]
1. **강의 톤**: 실제 강의실에서 학생들에게 말하듯이, 친절하고 이해하기 쉬운 구어체로 설명해야 합니다.
2. **내용 균형**: [강의 자료]로 입력된 내용 전체를 골고루 다루어야 합니다.
3. **순차적 흐름과 반복 절대 금지**: 한 번 설명한 개념을 뒤에서 똑같이 다시 반복하는 것은 절대 금지합니다.
4. **사고 유도형 질문**: 강의 설명 중간 혹은 마지막에 최소 1개에서 최대 4개까지 생성해야 합니다.
   - 형식: 반드시 `[질문]` 태그로 시작하고 `[/질문]` 태그로 끝나야 합니다.
5. **질문 생성 예외**: 내용이 단순한 개요, 목차, 서론인 경우 [질문]을 생성하지 않습니다.

# [Output]
학생들이 이해하기 쉬운 강의 설명과 함께, 사고를 유도하는 질문을 [질문][/질문] 형식으로 포함하여 작성해주세요.
"""

DETAILED_SUFFIX = """
# [Detail Mode: DETAILED]
이 챕터는 학습자가 이전에 낮은 점수를 받거나 약점으로 표시된 내용입니다.
- 개념을 더욱 깊이 있고 자세하게 설명해주세요.
- 구체적인 예시와 비유를 적극 활용해주세요.
- 핵심 용어를 명확히 정의해주세요.
"""


class ExplainerAgent:
    """
    페이지/챕터 설명을 스트리밍으로 생성하는 에이전트.
    ToolDispatcher 에서 EXPLAIN_PAGE 툴 호출 시 사용.
    """

    def __init__(self, bridge: GeminiBridgeClient):
        self._bridge = bridge

    async def run_stream(
        self,
        chapter_title: str,
        pdf_path: str,
        md_path: Optional[str] = None,
        detail: str = "NORMAL",
    ) -> AsyncGenerator[NdjsonEvent, None]:
        """
        강의 설명 스트리밍 생성.

        Args:
            chapter_title: 챕터 제목
            pdf_path: PDF 파일 경로
            md_path: 마크다운 대본 파일 경로 (선택)
            detail: "NORMAL" | "DETAILED" (복습 모드)
        """
        system = SYSTEM_PROMPT + (DETAILED_SUFFIX if detail == "DETAILED" else "")

        pdf_part = self._bridge.load_pdf_part(pdf_path)
        contents = [system, pdf_part]

        if md_path:
            md_text = self._bridge.load_text(md_path)
            contents.append(f"[강의 대본 초안 (MD) - 해당 챕터]\n{md_text}")

        user_prompt = (
            f"[현재 챕터]: {chapter_title}\n\n"
            "제공된 강의 대본 초안(MD)의 텍스트와 원본 시각 자료(PDF)를 모두 종합하여 강의를 진행해주세요.\n"
            "위 챕터 주제를 중심으로, 해당 챕터 자료 범위 안에서만 근거를 사용해 설명하세요.\n"
            "학생들의 사고를 확장시키는 질문을 1~2개 포함해주세요.\n"
        )
        contents.append(user_prompt)

        async for event in self._bridge.stream(contents):
            yield event

    async def run(
        self,
        chapter_title: str,
        pdf_path: str,
        md_path: Optional[str] = None,
        detail: str = "NORMAL",
    ) -> str:
        """비스트리밍 버전: 전체 설명 텍스트 반환"""
        system = SYSTEM_PROMPT + (DETAILED_SUFFIX if detail == "DETAILED" else "")

        pdf_part = self._bridge.load_pdf_part(pdf_path)
        contents = [system, pdf_part]

        if md_path:
            md_text = self._bridge.load_text(md_path)
            contents.append(f"[강의 대본 초안 (MD) - 해당 챕터]\n{md_text}")

        user_prompt = (
            f"[현재 챕터]: {chapter_title}\n\n"
            "제공된 자료를 종합하여 강의를 진행해주세요.\n"
        )
        contents.append(user_prompt)

        return await self._bridge.generate(contents)
