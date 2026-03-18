"""
GraderAgent

설계서 §5.4: GraderAgent
- 입력: quiz_type, problems, user_answers, lecture_content
- 출력: 채점 결과 JSON (done.data 필드로 전달)
- MCQ/OX: 서버 내부 정답 비교 (LLM 불필요)
- SHORT/ESSAY: LLM 채점 (MainQandAAgent의 run_qa_flow_async 활용)
"""
from __future__ import annotations

import json
from typing import Any, AsyncGenerator, Dict, List, Optional

from ai_agent.bridge.GeminiBridgeClient import GeminiBridgeClient
from ai_agent.types.domain import NdjsonEvent, NdjsonEventType

GRADING_SYSTEM_PROMPT = """# [Role]
당신은 전문 채점관입니다. 학생의 단답형/서술형 답변을 공정하게 채점해야 합니다.

# [Task]
주어진 문제, 모범 답안, 학생 답변을 검토하여 채점 결과를 JSON 형식으로 반환하세요.

# [Output Format]
{
  "results": [
    {
      "question_index": 0,
      "score": 0.8,
      "passed": true,
      "feedback": "핵심 개념을 잘 설명했으나 예시가 부족합니다."
    }
  ],
  "total_score": 0.8,
  "overall_feedback": "전반적으로 잘 이해하고 있습니다."
}

# [Rules]
- score는 0.0 ~ 1.0 사이의 소수점 값
- passed는 score >= 0.6 이면 true
- feedback은 구체적이고 건설적으로 작성
- 반드시 위 JSON 형식만 출력
"""

PASS_SCORE_RATIO = 0.6


class GraderAgent:
    """
    퀴즈 채점 에이전트.
    ToolDispatcher 에서 AUTO_GRADE_MCQ_OX / GRADE_SHORT_OR_ESSAY 툴 호출 시 사용.
    """

    def __init__(self, bridge: GeminiBridgeClient):
        self._bridge = bridge

    async def run_stream(
        self,
        quiz_type: str,
        problems: List[Dict[str, Any]],
        user_answers: List[Any],
        lecture_content: str = "",
    ) -> AsyncGenerator[NdjsonEvent, None]:
        """
        채점 스트리밍.

        Args:
            quiz_type: "Five_Choice" | "OX_Problem" | "Short_Answer" 등
            problems: 생성된 문제 리스트
            user_answers: 사용자 답변 리스트
            lecture_content: 강의 자료 텍스트 (단답/서술 채점용)
        """
        yield NdjsonEvent(
            type=NdjsonEventType.THOUGHT_DELTA,
            delta="채점을 진행 중입니다...",
        )

        try:
            if quiz_type in ("Five_Choice", "OX_Problem"):
                result = self._grade_auto(problems, user_answers)
            else:
                result = await self._grade_llm(problems, user_answers, lecture_content)

            yield NdjsonEvent(
                type=NdjsonEventType.DONE,
                data={"grading": result, "passed": result.get("total_score", 0) >= PASS_SCORE_RATIO},
            )
        except Exception as exc:
            yield NdjsonEvent(
                type=NdjsonEventType.ERROR,
                message=f"채점 실패: {exc}",
            )

    def _grade_auto(
        self,
        problems: List[Dict[str, Any]],
        user_answers: List[Any],
    ) -> Dict[str, Any]:
        """MCQ/OX 자동 채점 (서버 내부 정답 비교)"""
        results = []
        correct_count = 0

        for idx, (problem, user_answer) in enumerate(zip(problems, user_answers)):
            correct = problem.get("answer") or problem.get("correct_answer")
            is_correct = str(user_answer).strip().upper() == str(correct).strip().upper()
            if is_correct:
                correct_count += 1

            results.append({
                "question_index": idx,
                "score": 1.0 if is_correct else 0.0,
                "passed": is_correct,
                "feedback": "정답입니다!" if is_correct else f"오답입니다. 정답은 '{correct}' 입니다.",
            })

        total = correct_count / max(len(problems), 1)
        return {
            "results": results,
            "total_score": total,
            "overall_feedback": f"{len(problems)}문항 중 {correct_count}문항 정답 ({total*100:.0f}점)",
        }

    async def _grade_llm(
        self,
        problems: List[Dict[str, Any]],
        user_answers: List[Any],
        lecture_content: str,
    ) -> Dict[str, Any]:
        """단답/서술형 LLM 채점"""
        problems_text = json.dumps(problems, ensure_ascii=False, indent=2)
        answers_text = json.dumps(
            [{"index": i, "answer": a} for i, a in enumerate(user_answers)],
            ensure_ascii=False,
            indent=2,
        )

        user_prompt = (
            f"[강의 자료]\n{lecture_content[:3000]}\n\n"
            f"[문제 목록]\n{problems_text}\n\n"
            f"[학생 답변]\n{answers_text}\n\n"
            "위 학생 답변을 채점하고 JSON 형식으로 결과를 반환해주세요."
        )

        response_text = await self._bridge.generate([GRADING_SYSTEM_PROMPT, user_prompt])

        try:
            import re
            cleaned = re.sub(r"```json\s*", "", response_text)
            cleaned = re.sub(r"```\s*$", "", cleaned).strip()
            return json.loads(cleaned)
        except json.JSONDecodeError:
            return {
                "results": [],
                "total_score": 0.0,
                "overall_feedback": "채점 결과를 파싱할 수 없습니다.",
                "raw": response_text,
            }

    async def run(
        self,
        quiz_type: str,
        problems: List[Dict[str, Any]],
        user_answers: List[Any],
        lecture_content: str = "",
    ) -> Dict[str, Any]:
        """비스트리밍 버전"""
        if quiz_type in ("Five_Choice", "OX_Problem"):
            return self._grade_auto(problems, user_answers)
        return await self._grade_llm(problems, user_answers, lecture_content)
