# -*- coding: utf-8 -*-
"""
QuizAgents

Design doc section 5.3: QuizAgents
- Input: quiz_type, lecture_content, profile, count
- Output: normalized QuizJson (passed via done.data)
- Delegates to LectureTestGenerator generators via ToolDispatcher interface

v2.8 변경사항
- profile None/partial 허용: build_default_profile_dict + deep-merge로 항상 완성형 TestProfile 생성
- state.learner 딕셔너리(proficiency_level, weak_concepts)로 기본값 보강
- SSE error 이벤트: NdjsonEvent.code / .details 필드 사용 (표준화)
"""
from __future__ import annotations

import asyncio
from typing import Any, AsyncGenerator, Dict, List, Optional

from ai_agent.bridge.GeminiBridgeClient import GeminiBridgeClient
from ai_agent.types.domain import NdjsonEvent, NdjsonEventType
from ai_agent.v3.exam_type_aliases import normalize_exam_type_string

_HEARTBEAT_INTERVAL = 10.0

# LearnerModel.proficiency_level (UPPER) → ProficiencyLevel enum 값 매핑
_PROFICIENCY_MAP: Dict[str, str] = {
    "BEGINNER": "Beginner",
    "INTERMEDIATE": "Intermediate",
    "ADVANCED": "Advanced",
}


# ---------------------------------------------------------------------------
# 모듈 수준 헬퍼 (테스트에서도 독립적으로 사용 가능)
# ---------------------------------------------------------------------------

def build_default_profile_dict(learner_hint: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    """
    TestProfile 4개 required 필드가 모두 채워진 완성형 기본 프로필 딕셔너리 반환.
    learner_hint는 SessionState.learner.model_dump() 결과를 기대한다.
    """
    default: Dict[str, Any] = {
        "learning_goal": {
            "focus_areas": [],
            "target_depth": "Concept",
            "question_modality": "Balance",
        },
        "user_status": {
            "proficiency_level": "Intermediate",
            "weakness_focus": None,
        },
        "interaction_style": {
            "language_preference": "Korean_with_English_Terms",
            "scenario_based": False,
        },
        "feedback_preference": {
            "strictness": "Moderate",
            "explanation_depth": "Detailed_with_Examples",
        },
        "scope_boundary": "Lecture_Material_Only",
    }

    if not learner_hint or not isinstance(learner_hint, dict):
        return default

    # proficiency_level 보강
    raw_level = str(learner_hint.get("proficiency_level", "")).upper()
    default["user_status"]["proficiency_level"] = _PROFICIENCY_MAP.get(raw_level, "Intermediate")

    # 취약 개념이 있으면 약점 기반 출제 신호 활성화
    weak_concepts = learner_hint.get("weak_concepts")
    if isinstance(weak_concepts, list) and weak_concepts:
        default["user_status"]["weakness_focus"] = True
        default["learning_goal"]["focus_areas"] = [str(c) for c in weak_concepts[:10]]

    return default


def merge_profile(
    profile: Optional[Dict[str, Any]],
    learner_hint: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """
    partial profile 딕셔너리를 기본값과 딥 머지한다.
    - profile이 None/빈 dict → 기본값만 반환
    - profile에 일부 섹션만 있어도 나머지는 기본값으로 보완 (partial tolerant)
    """
    base = build_default_profile_dict(learner_hint)

    if not profile or not isinstance(profile, dict):
        return base

    def _deep_merge(a: Any, b: Any) -> Any:
        if isinstance(a, dict) and isinstance(b, dict):
            merged = dict(a)
            for k, v in b.items():
                merged[k] = _deep_merge(merged[k], v) if k in merged else v
            return merged
        return b

    return _deep_merge(base, profile)


def _build_validation_error_event(exc: Exception) -> NdjsonEvent:
    """pydantic ValidationError → 표준 SSE error 이벤트 변환."""
    details: List[Dict[str, Any]] = []
    try:
        for err in exc.errors():  # type: ignore[attr-defined]
            loc = ".".join(str(x) for x in (err.get("loc") or [])) or "(root)"
            details.append({"field": loc, "reason": err.get("msg", "invalid")})
    except AttributeError:
        details.append({"field": "profile", "reason": str(exc)})

    return NdjsonEvent(
        type=NdjsonEventType.ERROR,
        agent="quiz",
        code="QUIZ_PROFILE_VALIDATION_FAILED",
        message="Invalid profile for quiz generation",
        details=details,
    )


# ---------------------------------------------------------------------------
# Agent
# ---------------------------------------------------------------------------

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
        learner_hint: Optional[Dict[str, Any]] = None,
        count: int = 5,
    ) -> AsyncGenerator[NdjsonEvent, None]:
        """
        Quiz generation stream.

        Args:
            quiz_type: "Five_Choice" | "OX_Problem" | "Flash_Card" | "Short_Answer" | "Debate"
            lecture_content: 강의 자료 텍스트
            profile: 유저 프로필 딕셔너리 (None/partial 모두 허용 — 기본값으로 보완됨)
            learner_hint: SessionState.learner.model_dump() 결과 (proficiency, 취약점 보강용)
            count: 생성할 문제 수
        """
        yield NdjsonEvent(
            type=NdjsonEventType.AGENT_DELTA,
            agent="quiz",
            tool="GENERATE_QUIZ",
            channel="thought",
            delta=f"Generating {count} {quiz_type} quiz questions...",
        )

        quiz_task = asyncio.ensure_future(
            self._generate_quiz(quiz_type, lecture_content, profile, learner_hint, count)
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
            # pydantic ValidationError → 구조화 에러
            if hasattr(exc, "errors"):
                yield _build_validation_error_event(exc)
            else:
                yield NdjsonEvent(
                    type=NdjsonEventType.ERROR,
                    agent="quiz",
                    code="QUIZ_GENERATION_FAILED",
                    message=f"Quiz generation failed ({quiz_type}): {exc}",
                    details=[{"reason": str(exc)}],
                )

    async def _generate_quiz(
        self,
        quiz_type: str,
        lecture_content: str,
        profile: Optional[Dict[str, Any]],
        learner_hint: Optional[Dict[str, Any]],
        count: int,
    ) -> Any:
        from ai_agent.v2.test_gen.schemas import ExamType, ProblemRequest, TestProfile

        exam_type_map = {
            "Five_Choice": ExamType.FIVE_CHOICE,
            "OX_Problem": ExamType.OX_PROBLEM,
            "Flash_Card": ExamType.FLASH_CARD,
            "Short_Answer": ExamType.SHORT_ANSWER,
            "Debate": ExamType.DEBATE,
        }
        qt = normalize_exam_type_string(quiz_type)
        exam_type = exam_type_map.get(qt, ExamType.FIVE_CHOICE)

        merged = merge_profile(profile, learner_hint)
        test_profile = TestProfile.model_validate(merged)  # ValidationError는 run_stream에서 처리

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
        learner_hint: Optional[Dict[str, Any]] = None,
        count: int = 5,
    ) -> Any:
        """Non-streaming version."""
        return await self._generate_quiz(quiz_type, lecture_content, profile, learner_hint, count)
