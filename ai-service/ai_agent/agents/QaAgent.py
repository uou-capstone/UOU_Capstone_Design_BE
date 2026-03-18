"""
QaAgent

설계서 §5.2: QaAgent
- 입력: question(사용자 질문), pdf_path, chapter_title(컨텍스트)
- 출력: thought_delta / answer_delta / done (NDJSON 스트리밍)
- 기존 MainQandAAgent의 Q&A 흐름을 래핑
"""
from __future__ import annotations

from typing import AsyncGenerator, Optional

from ai_agent.bridge.GeminiBridgeClient import GeminiBridgeClient
from ai_agent.types.domain import NdjsonEvent

SYSTEM_PROMPT = """# [Role]
당신은 학생의 질문에 친절하고 정확하게 답변하는 전문 강의 보조 교수입니다.

# [Task]
학생이 강의 자료에 대해 질문을 했습니다. 제공된 PDF 강의 자료와 현재 학습 중인 챕터를 바탕으로,
학생의 질문에 명확하고 이해하기 쉽게 답변해주세요.

# [Rules]
1. 답변은 반드시 PDF 강의 자료에 근거해야 합니다.
2. 추측이나 외부 지식보다는 강의 자료의 내용을 우선합니다.
3. 학생이 이해하기 쉽도록 구어체로 설명해주세요.
4. 필요한 경우 예시나 비유를 활용해주세요.
5. 답변이 끝난 후 학생의 이해를 확인하는 짧은 질문을 추가해도 좋습니다.
"""


class QaAgent:
    """
    사용자 질문에 답변하는 에이전트.
    ToolDispatcher 에서 ANSWER_QUESTION 툴 호출 시 사용.
    """

    def __init__(self, bridge: GeminiBridgeClient):
        self._bridge = bridge

    async def run_stream(
        self,
        question: str,
        pdf_path: str,
        chapter_title: Optional[str] = None,
    ) -> AsyncGenerator[NdjsonEvent, None]:
        """
        질문에 대한 답변 스트리밍 생성.

        Args:
            question: 사용자 질문 텍스트
            pdf_path: 강의 자료 파일 경로 (PDF 또는 MD)
            chapter_title: 현재 챕터 제목 (컨텍스트 제공용)
        """
        import pathlib
        path = pathlib.Path(pdf_path)
        ext = path.suffix.lower()

        if ext == ".pdf":
            material_part = self._bridge.load_pdf_part(pdf_path)
            contents = [SYSTEM_PROMPT, material_part]
        else:
            material_text = self._bridge.load_text(pdf_path)
            contents = [SYSTEM_PROMPT, material_text]

        context_prefix = f"[현재 챕터]: {chapter_title}\n\n" if chapter_title else ""
        user_prompt = f"{context_prefix}[학생 질문]: {question}\n\n위 질문에 답변해주세요."
        contents.append(user_prompt)

        async for event in self._bridge.stream(contents):
            yield event

    async def run(
        self,
        question: str,
        pdf_path: str,
        chapter_title: Optional[str] = None,
    ) -> str:
        """비스트리밍 버전"""
        import pathlib
        path = pathlib.Path(pdf_path)
        ext = path.suffix.lower()

        if ext == ".pdf":
            material_part = self._bridge.load_pdf_part(pdf_path)
            contents = [SYSTEM_PROMPT, material_part]
        else:
            material_text = self._bridge.load_text(pdf_path)
            contents = [SYSTEM_PROMPT, material_text]

        context_prefix = f"[현재 챕터]: {chapter_title}\n\n" if chapter_title else ""
        user_prompt = f"{context_prefix}[학생 질문]: {question}\n\n위 질문에 답변해주세요."
        contents.append(user_prompt)

        return await self._bridge.generate(contents)
