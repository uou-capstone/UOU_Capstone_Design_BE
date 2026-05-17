from __future__ import annotations

import json
from typing import Any, AsyncGenerator

from ai_agent.bridge.GeminiBridgeClient import GeminiBridgeClient
from ai_agent.types.domain import NdjsonEvent, NdjsonEventType
from ai_agent.v3.engine.LearningContextCollector import LearningContext


REPAIR_PROMPT = """너는 오개념 교정 에이전트다.
학생은 방금 현재 페이지 퀴즈에서 낮은 점수를 받았고, 이어서 자신의 생각이나 질문을 남겼다.
반드시 자연스러운 한국어 Markdown으로 답변하라.
인사말, AI 자기소개, 새 수업 도입 문구 없이 바로 교정을 시작하라.

목표:
1. 학생 답변에서 드러난 오개념 또는 빠진 연결고리를 1개만 먼저 짚는다.
2. 정답을 통째로 외우게 하지 말고, 왜 헷갈렸는지 짧게 설명한다.
3. 현재 페이지 텍스트와 직전 퀴즈 오답 근거를 우선 사용한다.
4. 마지막에는 다시 풀 준비가 되었는지 확인하는 짧은 질문을 붙인다.

포맷 규칙:
- 중요한 개념, 조건, 오개념 이름은 **굵게** 표시한다.
- 수식은 LaTeX 문법을 사용한다. 예: `$a/b + c/d$`
- 코드, 명령어, 파일명, 키워드는 코드 포맷을 사용한다. 예: `HTTP`
- 2~4개의 짧은 문단 또는 글머리 기호로 구성한다.
- 첫 줄은 `## 오개념 교정` 헤딩으로 시작한다.

활성 교정 상태:
{intervention}

학생 메시지:
{student_message}

현재 페이지 텍스트:
{page_text}

이전 페이지 참고:
{prev_text}

다음 페이지 참고:
{next_text}

학습자 메모리:
{learner_memory_digest}
"""


class MisconceptionRepairAgent:
    def __init__(self, bridge: GeminiBridgeClient):
        self._bridge = bridge

    async def run_stream(
        self,
        *,
        student_message: str,
        intervention: dict[str, Any],
        learning_context: LearningContext,
    ) -> AsyncGenerator[NdjsonEvent, None]:
        yield NdjsonEvent(
            type=NdjsonEventType.AGENT_DELTA,
            agent="repair",
            tool="REPAIR_MISCONCEPTION",
            channel="thought",
            delta="오답 패턴과 학생 응답을 연결해 교정 설명을 준비하고 있습니다.",
        )
        prompt = self._build_prompt(
            student_message=student_message,
            intervention=intervention,
            learning_context=learning_context,
        )
        async for event in self._bridge.stream([prompt], agent="repair", tool="REPAIR_MISCONCEPTION"):
            yield event

    def _build_prompt(
        self,
        *,
        student_message: str,
        intervention: dict[str, Any],
        learning_context: LearningContext,
    ) -> str:
        return REPAIR_PROMPT.format(
            intervention=json.dumps(intervention, ensure_ascii=False, indent=2),
            student_message=student_message.strip() or "(학생 메시지 없음)",
            page_text=(learning_context.page_text or "").strip() or "(현재 페이지 텍스트 없음)",
            prev_text=(learning_context.prev_text or "").strip() or "(없음)",
            next_text=(learning_context.next_text or "").strip() or "(없음)",
            learner_memory_digest=(learning_context.learner_memory_digest or "").strip() or "(없음)",
        )
