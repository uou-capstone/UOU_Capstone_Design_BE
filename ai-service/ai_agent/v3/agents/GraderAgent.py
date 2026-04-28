"""
GraderAgent

설계서 §5.4: GraderAgent
- 입력: quiz_type, problems, user_answers, lecture_content
- 출력: 채점 결과 JSON (done.data 필드로 전달)
- MCQ/OX: 서버 내부 정답 비교 (LLM 불필요)
- SHORT/ESSAY: LLM 채점 (MainQandAAgent의 run_qa_flow_async 활용)
"""
from __future__ import annotations

import asyncio
import json
from typing import Any, AsyncGenerator, Dict, List, Optional

from ai_agent.bridge.GeminiBridgeClient import GeminiBridgeClient
from ai_agent.types.domain import NdjsonEvent, NdjsonEventType
from ai_agent.v3.exam_type_aliases import normalize_exam_type_string

_HEARTBEAT_INTERVAL = 10.0

GRADING_SYSTEM_PROMPT = """# [Role]
당신은 전문 채점관입니다. 학생의 단답형/서술형 답변을 공정하게 채점해야 합니다.

# [Task]
주어진 강의 자료(PDF 및 텍스트), 문제 정보, 학생 답변을 검토하여 채점 결과를 JSON 형식으로 반환하세요.

# [Output Format]
반드시 아래 JSON 형식만 출력하세요. 마크다운 코드블록 없이 순수 JSON만 반환하세요.
{
  "results": [
    {
      "question_index": 0,
      "score": 0.8,
      "passed": true,
      "reason": "핵심 키워드를 포함하고 출제 의도에 맞게 답변하였습니다.",
      "feedback": "핵심 개념을 잘 설명했으나 예시가 부족합니다.",
      "deduction_reason": "구체적인 예시를 들지 않아 0.2점 감점하였습니다."
    }
  ],
  "total_score": 0.8,
  "overall_feedback": "전반적으로 잘 이해하고 있습니다."
}

# [Rules]
- score는 0.0 ~ 1.0 사이의 값으로, 0.1 단위로 부여하세요 (예: 0.0, 0.1, ..., 1.0)
- 최종 total_score = 각 문제 score 합 / 문제 수
- passed는 score >= 0.6 이면 true
- reason: 해당 점수를 부여한 채점 근거 (필수)
- feedback: 학생에게 전달할 구체적이고 건설적인 피드백 (필수)
- deduction_reason: 점수가 1.0 미만인 경우 감점 사유 (만점이면 빈 문자열 "")
- 반드시 위 JSON 형식만 출력, 다른 텍스트 없이
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
        pdf_path: Optional[str] = None,
    ) -> AsyncGenerator[NdjsonEvent, None]:
        """
        채점 스트리밍.

        Args:
            quiz_type: "Five_Choice" | "OX_Problem" | "Short_Answer" 등
            problems: 생성된 문제 리스트
            user_answers: 사용자 답변 리스트
            lecture_content: 강의 자료 텍스트 (단답/서술 채점용)
            pdf_path: 강의 PDF 경로 (단답/서술 채점 시 Gemini에 함께 전달)
        """
        quiz_type = normalize_exam_type_string(quiz_type)
        yield NdjsonEvent(
            type=NdjsonEventType.AGENT_DELTA,
            agent="grader",
            tool="GRADE",
            channel="thought",
            delta="채점을 진행 중입니다...",
        )

        try:
            if quiz_type in ("Five_Choice", "OX_Problem"):
                result = self._grade_auto(problems, user_answers)
                yield NdjsonEvent(
                    type=NdjsonEventType.DONE,
                    agent="grader",
                    tool="GRADE",
                    final=True,
                    data={"grading": result, "passed": result.get("total_score", 0) >= PASS_SCORE_RATIO},
                )
            else:
                # LLM 채점은 시간이 걸리므로 heartbeat 삽입
                grade_task = asyncio.ensure_future(
                    self._grade_llm(problems, user_answers, lecture_content, pdf_path)
                )
                try:
                    while True:
                        try:
                            result = await asyncio.wait_for(
                                asyncio.shield(grade_task), timeout=_HEARTBEAT_INTERVAL
                            )
                            break
                        except asyncio.TimeoutError:
                            yield NdjsonEvent(type=NdjsonEventType.HEARTBEAT)
                    yield NdjsonEvent(
                        type=NdjsonEventType.DONE,
                        agent="grader",
                        tool="GRADE",
                        final=True,
                        data={"grading": result, "passed": result.get("total_score", 0) >= PASS_SCORE_RATIO},
                    )
                except Exception as exc:
                    grade_task.cancel()
                    raise exc
        except Exception as exc:
            yield NdjsonEvent(
                type=NdjsonEventType.ERROR,
                agent="grader",
                message=f"채점 실패: {exc}",
            )

    def _grade_auto(
        self,
        problems: List[Dict[str, Any]],
        user_answers: List[Any],
    ) -> Dict[str, Any]:
        """MCQ/OX 자동 채점 (서버 내부 정답 비교)"""
        if len(problems) != len(user_answers):
            raise ValueError(
                f"문제 수({len(problems)})와 답안 수({len(user_answers)})가 일치하지 않습니다."
            )

        results = []
        correct_count = 0

        for idx, (problem, user_answer) in enumerate(zip(problems, user_answers)):
            correct = problem.get("answer") or problem.get("correct_answer")
            # dict 형식 {"index": i, "answer": "..."} 또는 단순 문자열 모두 처리
            answer_str = (
                user_answer.get("answer", "") if isinstance(user_answer, dict) else str(user_answer)
            )
            is_correct = answer_str.strip().upper() == str(correct).strip().upper()
            if is_correct:
                correct_count += 1

            results.append({
                "question_index": idx,
                "score": 1.0 if is_correct else 0.0,
                "passed": is_correct,
                "feedback": "정답입니다!" if is_correct else f"오답입니다. 정답은 '{correct}' 입니다.",
                "user_answer": answer_str,
                "correct_answer": str(correct),
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
        pdf_path: Optional[str] = None,
    ) -> Dict[str, Any]:
        """
        단답/서술형 Gemini AI 채점.
        pdf_path가 있으면 PDF를 Gemini에 직접 전달하여 정확도를 높입니다.
        """
        import re

        problems_text = json.dumps(problems, ensure_ascii=False, indent=2)
        answers_text = json.dumps(
            [{"index": i, "answer": a} for i, a in enumerate(user_answers)],
            ensure_ascii=False,
            indent=2,
        )

        user_prompt = (
            "[문제 목록 (문제번호, 문제내용, 핵심키워드, 출제의도 포함)]\n"
            f"{problems_text}\n\n"
            f"[학생 답변]\n{answers_text}\n\n"
            "위 학생 답변을 채점하고 지정된 JSON 형식으로 결과를 반환해주세요.\n"
            "반드시 순수 JSON만 반환하고, 마크다운 코드블록은 사용하지 마세요."
        )

        # PDF가 있으면 함께 전달, 없으면 텍스트만
        if pdf_path:
            try:
                pdf_part = await self._bridge.load_pdf_part(pdf_path)
                contents = [GRADING_SYSTEM_PROMPT, pdf_part, user_prompt]
            except Exception:
                # PDF 로딩 실패 시 텍스트로 fallback
                contents = [
                    GRADING_SYSTEM_PROMPT,
                    f"[강의 자료 텍스트]\n{lecture_content[:4000]}\n\n" + user_prompt,
                ]
        else:
            contents = [
                GRADING_SYSTEM_PROMPT,
                f"[강의 자료 텍스트]\n{lecture_content[:4000]}\n\n" + user_prompt,
            ]

        response_text = await self._bridge.generate(contents)

        try:
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
        pdf_path: Optional[str] = None,
    ) -> Dict[str, Any]:
        """비스트리밍 버전"""
        quiz_type = normalize_exam_type_string(quiz_type)
        if quiz_type in ("Five_Choice", "OX_Problem"):
            return self._grade_auto(problems, user_answers)
        return await self._grade_llm(problems, user_answers, lecture_content, pdf_path)
