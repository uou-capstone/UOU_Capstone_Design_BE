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

_PROBLEM_KEYS_BY_TYPE: Dict[str, str] = {
    "Five_Choice": "mcq_problems",
    "OX_Problem": "ox_problems",
    "Flash_Card": "flash_cards",
    "Short_Answer": "short_answer_problems",
    "Essay": "short_answer_problems",
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


def _to_jsonable(value: Any) -> Any:
    if hasattr(value, "model_dump"):
        return value.model_dump(mode="json")
    if isinstance(value, list):
        return [_to_jsonable(item) for item in value]
    if isinstance(value, dict):
        return {k: _to_jsonable(v) for k, v in value.items()}
    return value


def _as_problem_list(value: Any) -> List[Dict[str, Any]]:
    value = _to_jsonable(value)
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, dict)]


def normalize_quiz_generation_result(result: Any, quiz_type: str) -> List[Dict[str, Any]]:
    """
    Normalize v2 TestGenerationResponse wrappers to the v3 session contract.

    The session/bridge layer expects done.data.quiz to be the problem array.
    The v2 generator returns {"problems": {"mcq_problems": [...]}} style
    wrappers, so this extracts the actual list while accepting older shapes.
    """
    normalized_type = normalize_exam_type_string(quiz_type)
    raw = _to_jsonable(result)
    if isinstance(raw, list):
        return _normalize_problem_items(_as_problem_list(raw), normalized_type)
    if not isinstance(raw, dict):
        return []

    if "quiz" in raw:
        return normalize_quiz_generation_result(raw["quiz"], quiz_type)

    expected_key = _PROBLEM_KEYS_BY_TYPE.get(normalized_type)

    problems = raw.get("problems")
    if isinstance(problems, list):
        return _normalize_problem_items(_as_problem_list(problems), normalized_type)
    if isinstance(problems, dict):
        if expected_key and expected_key in problems:
            return _normalize_problem_items(_as_problem_list(problems[expected_key]), normalized_type)
        for key in (*_PROBLEM_KEYS_BY_TYPE.values(), "topics"):
            if key in problems:
                return _normalize_problem_items(_as_problem_list(problems[key]), normalized_type)

    if expected_key and expected_key in raw:
        return _normalize_problem_items(_as_problem_list(raw[expected_key]), normalized_type)
    for key in (*_PROBLEM_KEYS_BY_TYPE.values(), "topics"):
        if key in raw:
            return _normalize_problem_items(_as_problem_list(raw[key]), normalized_type)

    return []


def _normalize_problem_items(items: List[Dict[str, Any]], quiz_type: str) -> List[Dict[str, Any]]:
    if quiz_type != "Flash_Card":
        return items
    return [_normalize_flash_card_item(item) for item in items]


def _normalize_flash_card_item(item: Dict[str, Any]) -> Dict[str, Any]:
    normalized = dict(item)
    front = _first_non_empty_text(
        normalized,
        "front",
        "frontContent",
        "front_content",
        "cardFront",
        "term",
        "cue",
        "keyword",
    )
    back = _first_non_empty_text(
        normalized,
        "back",
        "backContent",
        "back_content",
        "cardBack",
        "definition",
        "answer",
        "explanation",
        "meaning",
    )
    if front:
        normalized.setdefault("front", front)
        normalized.setdefault("frontContent", front)
        normalized.setdefault("front_content", front)
    if back:
        normalized.setdefault("back", back)
        normalized.setdefault("backContent", back)
        normalized.setdefault("back_content", back)
    return normalized


def _first_non_empty_text(item: Dict[str, Any], *keys: str) -> str:
    for key in keys:
        value = item.get(key)
        if value is None:
            continue
        text = str(value).strip()
        if text:
            return text
    return ""


def _merge_reference_quiz_profile(
    *,
    quiz_type: str,
    count: int,
    merged_profile: Dict[str, Any],
    learner_hint: Optional[Dict[str, Any]],
) -> Dict[str, Any]:
    """
    Keep ProblemRequest.lecture_content as pure lecture text.

    The v2 generator treats lecture_content as material, so reference-style
    generation guidance belongs in the profile where it is metadata.
    """
    profile_copy = _to_jsonable(merged_profile)
    if not isinstance(profile_copy, dict):
        profile_copy = {}

    weak_concepts = []
    if isinstance(learner_hint, dict) and isinstance(learner_hint.get("weak_concepts"), list):
        weak_concepts = [str(item) for item in learner_hint.get("weak_concepts", [])[:8]]

    learning_goal = profile_copy.setdefault("learning_goal", {})
    if not isinstance(learning_goal, dict):
        learning_goal = {}
        profile_copy["learning_goal"] = learning_goal

    focus_areas = learning_goal.get("focus_areas", [])
    if isinstance(focus_areas, list):
        weak_concepts.extend(str(item) for item in focus_areas[:8])
    deduped_weak = [item for item in dict.fromkeys(item for item in weak_concepts if item.strip())]
    learning_goal["focus_areas"] = deduped_weak[:10]
    learning_goal["target_depth"] = learning_goal.get("target_depth") or "Concept"
    learning_goal["question_modality"] = learning_goal.get("question_modality") or "Balance"

    user_status = profile_copy.setdefault("user_status", {})
    if isinstance(user_status, dict):
        user_status["weakness_focus"] = bool(deduped_weak)

    feedback = profile_copy.setdefault("feedback_preference", {})
    if isinstance(feedback, dict):
        feedback["strictness"] = feedback.get("strictness") or "Moderate"
        feedback["explanation_depth"] = feedback.get("explanation_depth") or "Detailed_with_Examples"

    profile_copy["scope_boundary"] = "Lecture_Material_Only"
    return profile_copy


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
        context_label: str = "현재 페이지",
    ) -> AsyncGenerator[NdjsonEvent, None]:
        """
        Quiz generation stream.

        Args:
            quiz_type: "Five_Choice" | "OX_Problem" | "Flash_Card" | "Short_Answer" | "Essay" | "Debate"
            lecture_content: 강의 자료 텍스트
            profile: 유저 프로필 딕셔너리 (None/partial 모두 허용 — 기본값으로 보완됨)
            learner_hint: SessionState.learner.model_dump() 결과 (proficiency, 취약점 보강용)
            count: 생성할 문제 수
        """
        quiz_type = normalize_exam_type_string(quiz_type)
        yield NdjsonEvent(
            type=NdjsonEventType.AGENT_DELTA,
            agent="quiz",
            tool="GENERATE_QUIZ",
            channel="thought",
            delta=f"{quiz_type} 퀴즈 {count}문항을 {context_label} 컨텍스트와 학습자 약점에 맞춰 생성하고 있습니다.",
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
            "Essay": ExamType.SHORT_ANSWER,
            "Debate": ExamType.DEBATE,
        }
        qt = normalize_exam_type_string(quiz_type)
        exam_type = exam_type_map.get(qt, ExamType.FIVE_CHOICE)

        merged = merge_profile(profile, learner_hint)
        reference_profile = _merge_reference_quiz_profile(
            quiz_type=qt,
            count=count,
            merged_profile=merged,
            learner_hint=learner_hint,
        )
        test_profile = TestProfile.model_validate(reference_profile)  # ValidationError는 run_stream에서 처리

        request = ProblemRequest(
            exam_type=exam_type,
            target_count=count,
            lecture_content=lecture_content,
            user_profile=test_profile,
        )

        generator = self._get_generator()
        result = await generator.generate_test(request)
        return normalize_quiz_generation_result(result, qt)

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
