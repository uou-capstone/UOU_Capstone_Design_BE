# -*- coding: utf-8 -*-
"""
QuizAgents

Design doc section 5.3: QuizAgents
- Input: quiz_type, lecture_content, profile, count
- Output: normalized QuizJson (passed via done.data)
- Delegates to LectureTestGenerator generators via ToolDispatcher interface
"""
from __future__ import annotations

import asyncio
from typing import Any, AsyncGenerator, Dict, Optional

from ai_agent.bridge.GeminiBridgeClient import GeminiBridgeClient
from ai_agent.types.domain import NdjsonEvent, NdjsonEventType

_HEARTBEAT_INTERVAL = 10.0


class QuizAgents:
    """
    Quiz generation agent.
    Used when ToolDispatcher calls GENERATE_QUIZ_* tools.
    Delegates internally to LectureTestGenerator generators.
    Stream consists of thought_delta (progress) and done (completion) phases only.
    """

    def __init__(self, bridge: GeminiBridgeClient):
        self._bridge = bridge
        self._generator_instance = None

    def _get_generator(self):
        if self._generator_instance is None:
            from ai_agent.v2.test_gen.main import LectureTestGenerator
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
        Quiz generation stream.

        Args:
            quiz_type: "Five_Choice" | "OX_Problem" | "Flash_Card" | "Short_Answer" | "Debate"
            lecture_content: Lecture material text
            profile: User profile dict (uses default profile if None)
            count: Number of problems to generate
        """
        yield NdjsonEvent(
            type=NdjsonEventType.AGENT_DELTA,
            agent="quiz",
            tool="GENERATE_QUIZ",
            channel="thought",
            delta=f"Generating {count} {quiz_type} quiz questions...",
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
                message=f"Quiz generation failed ({quiz_type}): {exc}",
            )

    async def _generate_quiz(
        self,
        quiz_type: str,
        lecture_content: str,
        profile: Optional[Dict[str, Any]],
        count: int,
    ) -> Any:
        from ai_agent.v2.test_gen.schemas import (
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
        """Non-streaming version."""
        return await self._generate_quiz(quiz_type, lecture_content, profile, count)
