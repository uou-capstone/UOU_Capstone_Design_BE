from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Any

from ai_agent.v3.agents.GraderAgent import PASS_SCORE_RATIO
from ai_agent.types.domain import QuizRecord, SessionState


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _text(value: Any, limit: int = 180) -> str:
    if value is None:
        return ""
    out = str(value).strip()
    return out if len(out) <= limit else out[:limit].rstrip() + "..."


class QuizDiagnosisService:
    """
    Converts low-score quiz results into a page-scoped repair intervention.
    """

    def record_assessment(
        self,
        state: SessionState,
        record: QuizRecord,
        grading: dict[str, Any],
        user_answers: list[Any],
    ) -> dict[str, Any]:
        score = self._score(grading)
        passed = score >= PASS_SCORE_RATIO
        missed_questions = self._missed_questions(record.questions, grading, user_answers)
        focus_concepts = self._focus_concepts(record, missed_questions, state)
        diagnostic_prompt = self._diagnostic_prompt(focus_concepts, missed_questions)
        repair_goal = self._repair_goal(focus_concepts)

        assessment = {
            "artifactId": f"assessment-{uuid.uuid4().hex[:12]}",
            "quizId": record.quiz_id,
            "pageNumber": record.page_number,
            "quizType": record.quiz_type,
            "status": "PASSED" if passed else "PENDING",
            "score": round(score, 4),
            "passed": passed,
            "focusConcepts": focus_concepts,
            "missedQuestions": missed_questions,
            "diagnosticPrompt": diagnostic_prompt,
            "repairGoal": repair_goal,
            "createdAt": _now(),
        }
        state.quiz_assessments.append(assessment)
        state.quiz_assessments = state.quiz_assessments[-20:]

        if passed:
            self._clear_resolved_intervention(state, record.page_number)
            return assessment

        state.active_intervention = {
            "interventionId": f"repair-{uuid.uuid4().hex[:12]}",
            "sourceArtifactId": assessment["artifactId"],
            "sourceQuizId": record.quiz_id,
            "pageNumber": record.page_number,
            "status": "AWAITING_USER_RESPONSE",
            "focusConcept": focus_concepts[0] if focus_concepts else "현재 페이지 핵심 개념",
            "focusConcepts": focus_concepts,
            "diagnosticPrompt": diagnostic_prompt,
            "repairGoal": repair_goal,
            "score": round(score, 4),
            "attempts": 0,
            "createdAt": assessment["createdAt"],
        }
        return assessment

    def start_repair(self, state: SessionState, student_message: str) -> dict[str, Any] | None:
        intervention = state.active_intervention
        if not intervention:
            return None
        self._mark_source_assessment_status(state, intervention, "REPAIR_IN_PROGRESS")
        intervention["status"] = "IN_PROGRESS"
        intervention["attempts"] = int(intervention.get("attempts") or 0) + 1
        intervention["lastStudentMessage"] = _text(student_message, 1000)
        intervention["updatedAt"] = _now()
        return intervention

    def complete_repair(self, state: SessionState, repair_summary: str) -> dict[str, Any] | None:
        intervention = state.active_intervention
        if not intervention:
            return None
        intervention["status"] = "COMPLETED"
        intervention["repairSummary"] = _text(repair_summary, 1200)
        intervention["completedAt"] = _now()
        self._mark_source_assessment_status(state, intervention, "REPAIR_COMPLETED")
        return intervention

    def consume_pending_assessment_digest(
        self,
        state: SessionState,
        *,
        page_number: int | None = None,
    ) -> str:
        pending = [
            item for item in state.quiz_assessments
            if item.get("status") == "PENDING"
            and (page_number is None or int(item.get("pageNumber") or 0) == page_number)
        ]
        if not pending:
            return ""

        lines: list[str] = []
        for item in pending[-3:]:
            concepts = ", ".join(item.get("focusConcepts") or []) or "현재 페이지 핵심 개념"
            missed_count = len(item.get("missedQuestions") or [])
            lines.append(
                f"- quizId={item.get('quizId')} score={item.get('score')} "
                f"missed={missed_count} focus={concepts}; goal={item.get('repairGoal')}"
            )
            item["status"] = "CONSUMED"
            item["consumedAt"] = _now()
        return "\n".join(lines)

    @staticmethod
    def has_pending_assessment(state: SessionState, *, page_number: int | None = None) -> bool:
        return any(
            item.get("status") == "PENDING"
            and (page_number is None or int(item.get("pageNumber") or 0) == page_number)
            for item in state.quiz_assessments
        )

    def _clear_resolved_intervention(self, state: SessionState, page_number: int) -> None:
        if not state.active_intervention:
            return
        if int(state.active_intervention.get("pageNumber") or 0) == page_number:
            state.active_intervention["status"] = "RESOLVED_BY_RETEST"
            state.active_intervention["resolvedAt"] = _now()
            self._mark_source_assessment_status(state, state.active_intervention, "RESOLVED_BY_RETEST")
            state.active_intervention = None

    @staticmethod
    def _mark_source_assessment_status(
        state: SessionState,
        intervention: dict[str, Any],
        status: str,
    ) -> None:
        source_artifact_id = intervention.get("sourceArtifactId")
        if not source_artifact_id:
            return
        for item in state.quiz_assessments:
            if item.get("artifactId") == source_artifact_id:
                item["status"] = status
                item["updatedAt"] = _now()
                return

    @staticmethod
    def _score(grading: dict[str, Any]) -> float:
        try:
            return float(grading.get("total_score", 0.0) or 0.0)
        except (TypeError, ValueError):
            return 0.0

    def _missed_questions(
        self,
        questions: list[dict[str, Any]],
        grading: dict[str, Any],
        user_answers: list[Any],
    ) -> list[dict[str, Any]]:
        results = grading.get("results") if isinstance(grading.get("results"), list) else []
        missed: list[dict[str, Any]] = []
        for index, result in enumerate(results):
            if not isinstance(result, dict):
                continue
            score = self._result_score(result)
            passed = bool(result.get("passed")) if "passed" in result else score >= PASS_SCORE_RATIO
            if passed and score >= PASS_SCORE_RATIO:
                continue
            question_index = self._question_index(result, index)
            question = questions[question_index] if 0 <= question_index < len(questions) else {}
            missed.append({
                "questionIndex": question_index,
                "question": self._question_text(question),
                "userAnswer": _text(result.get("user_answer") or self._answer_at(user_answers, question_index)),
                "correctAnswer": _text(result.get("correct_answer") or self._expected_answer(question)),
                "feedback": _text(result.get("feedback") or result.get("reason")),
                "concepts": self._concepts_from_question(question),
            })
        return missed[:6]

    @staticmethod
    def _result_score(result: dict[str, Any]) -> float:
        try:
            return float(result.get("score", 0.0) or 0.0)
        except (TypeError, ValueError):
            return 0.0

    @staticmethod
    def _question_index(result: dict[str, Any], fallback: int) -> int:
        try:
            return int(result.get("question_index", result.get("questionIndex", fallback)))
        except (TypeError, ValueError):
            return fallback

    @staticmethod
    def _answer_at(user_answers: list[Any], index: int) -> Any:
        if 0 <= index < len(user_answers):
            value = user_answers[index]
            if isinstance(value, dict):
                return value.get("answer", value.get("value", value))
            return value
        return None

    @staticmethod
    def _expected_answer(question: dict[str, Any]) -> Any:
        for key in ("answer", "correct_answer", "correctAnswer", "referenceAnswer"):
            if key in question:
                return question[key]
        return None

    @staticmethod
    def _question_text(question: dict[str, Any]) -> str:
        for key in ("question", "prompt", "question_content", "content", "text", "stem"):
            if question.get(key):
                return _text(question[key], 240)
        return "(문항 텍스트 없음)"

    def _focus_concepts(
        self,
        record: QuizRecord,
        missed_questions: list[dict[str, Any]],
        state: SessionState,
    ) -> list[str]:
        concepts: list[str] = []
        for item in missed_questions:
            for concept in item.get("concepts") or []:
                if concept and concept not in concepts:
                    concepts.append(concept)
        if not concepts:
            page_state = state.pages.get(record.page_number)
            if page_state and page_state.chapter_title:
                concepts.append(page_state.chapter_title)
        if not concepts:
            concepts.append("현재 페이지 핵심 개념")
        return concepts[:5]

    @staticmethod
    def _concepts_from_question(question: dict[str, Any]) -> list[str]:
        raw_values: list[Any] = []
        for key in ("concept", "concepts", "topic", "topics", "knowledgeTag", "intent_type", "intentType"):
            value = question.get(key)
            if value:
                raw_values.append(value)
        concepts: list[str] = []
        for value in raw_values:
            if isinstance(value, list):
                candidates = value
            else:
                candidates = [value]
            for candidate in candidates:
                text = _text(candidate, 80)
                if text and text not in concepts:
                    concepts.append(text)
        return concepts[:3]

    @staticmethod
    def _diagnostic_prompt(focus_concepts: list[str], missed_questions: list[dict[str, Any]]) -> str:
        focus = ", ".join(focus_concepts) or "현재 페이지 핵심 개념"
        missed = len(missed_questions)
        missed_text = f"{missed}개 오답이 나온 " if missed else ""
        return (
            f"이번에는 바로 전체 복습으로 가지 않고, **{focus}** 쪽에서 {missed_text}"
            "어디가 막혔는지 먼저 짚어볼게요.\n\n"
            "개념 자체가 헷갈렸는지, 적용 이유가 헷갈렸는지, 문제 풀이 과정이 헷갈렸는지 한 줄로 말해 주세요."
        )

    @staticmethod
    def _repair_goal(focus_concepts: list[str]) -> str:
        focus = ", ".join(focus_concepts) or "현재 페이지 핵심 개념"
        return f"학생이 {focus}을 자신의 말로 설명하고 유사 문항을 다시 풀 준비가 되게 한다."


quiz_diagnosis_service = QuizDiagnosisService()
