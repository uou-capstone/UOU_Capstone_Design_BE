"""
QuizAgents

설계서 §5.3: QuizAgents
- 입력: quiz_type, lecture_content, profile, count
- 출력: 정규화된 QuizJson (done.data 필드로 전달)
- LectureTestGenerator 의 각 Generator 를 ToolDispatcher 인터페이스로 래핑
"""
from __future__ import annotations

import asyncio
from typing import Any, AsyncGenerator, Dict, Optional

from ai_agent.bridge.GeminiBridgeClient import GeminiBridgeClient
from ai_agent.types.domain import NdjsonEvent, NdjsonEventType

_HEARTBEAT_INTERVAL = 10.0


class QuizAgents:
    """
    퀴즈 생성 에이전트.
    ToolDispatcher 에서 GENERATE_QUIZ_* 툴 호출 시 사용.

    내부적으로 LectureTestGenerator 의 각 Generator 에 위임한다.
    스트리밍은 생성 진행 상황(thought_delta)과 완료(done) 두 단계로만 구성된다.
    """

    def __init__(self, bridge: GeminiBridgeClient):
        self._bridge = bridge
        self._generator_instance = None

    def _get_generator(self):
        if self._generator_instance is None:
            from ai_agent.LectureTestGenerator.main import LectureTestGenerator
            self._generator_instance = LectureTestGenerator()
        return self._generator_instance

    async def run_stream(
        self,
        quiz_type: str,
        lecture_content: str,
        profile: Optional[Dict[str, Any]] = None,
        count: int = 5,
    ) -> AsyncGenerator[NdjsonEvent, None]:
        """
        퀴즈 생성 스트리밍.

        Args:
            quiz_type: "Five_Choice" | "OX_Problem" | "Flash_Card" | "Short_Answer" | "Debate"
            lecture_content: 강의 자료 텍스트
            profile: 사용자 프로필 딕셔너리 (없으면 기본 프로필)
            count: 생성할 문제 수
        """
        yield NdjsonEvent(
            type=NdjsonEventType.AGENT_DELTA,
            agent="quiz",
            tool="GENERATE_QUIZ",
            channel="thought",
            delta=f"{quiz_type} 유형 퀴즈 {count}문항을 생성 중입니다...",
        )

        quiz_task = asyncio.ensure_future(
            self._generate_quiz(quiz_type, lecture_content, profile, count)
        )
        try:
            while True:
                try:
                    quiz_data = await asyncio.wait_for(
                        asyncio.shield(quiz_task), timeout=_HEARTBEAT_INTERVAL
                    )
                    break
                except asyncio.TimeoutError:
                    yield NdjsonEvent(type=NdjsonEventType.HEARTBEAT)
            yield NdjsonEvent(
                type=NdjsonEventType.DONE,
                agent="quiz",
                tool="GENERATE_QUIZ",
                final=True,
                data={"quiz": quiz_data, "quiz_type": quiz_type},
            )
        except Exception as exc:
            quiz_task.cancel()
            yield NdjsonEvent(
                type=NdjsonEventType.ERROR,
                agent="quiz",
                message=f"퀴즈 생성 실패 ({quiz_type}): {exc}",
            )

    async def _generate_quiz(
        self,
        quiz_type: str,
        lecture_content: str,
        profile: Optional[Dict[str, Any]],
        count: int,
    ) -> Any:
        from ai_agent.LectureTestGenerator.schemas import (
            ExamType, ProblemRequest, TestProfile
        )

        exam_type_map = {
            "Five_Choice": ExamType.FIVE_CHOICE,
            "OX_Problem": ExamType.OX_PROBLEM,
            "Flash_Card": ExamType.FLASH_CARD,
            "Short_Answer": ExamType.SHORT_ANSWER,
            "Debate": ExamType.DEBATE,
        }
        exam_type = exam_type_map.get(quiz_type, ExamType.FIVE_CHOICE)

        test_profile = TestProfile.model_validate(profile) if profile else TestProfile()
        request = ProblemRequest(
            exam_type=exam_type,
            target_count=count,
            lecture_content=lecture_content,
            user_profile=test_profile,
        )

        generator = self._get_generator()
        result = await generator.generate_test(request)
        return result.model_dump() if hasattr(result, "model_dump") else result

    async def run(
        self,
        quiz_type: str,
        lecture_content: str,
        profile: Optional[Dict[str, Any]] = None,
        count: int = 5,
    ) -> Any:
        """비스트리밍 버전"""
        return await self._generate_quiz(quiz_type, lecture_content, profile, count)
