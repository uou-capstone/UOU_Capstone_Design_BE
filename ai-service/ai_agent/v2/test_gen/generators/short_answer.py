"""
ShortAnswerGenerator: 단답형 문제 생성기
Planner -> Writer 흐름을 비동기로 구현
"""
import asyncio
import json
from typing import List, Dict, Any
from google.genai import types

from .base import BaseGenerator
from ..schemas import (
    TestProfile,
    ShortAnswerProblem,
    ShortAnswerResponse,
    UserAnswer,
    ProblemFeedback
)
from ..prompts import (
    SHORT_ANSWER_PLANNER_SYSTEM_PROMPT,
    SHORT_ANSWER_WRITER_SYSTEM_PROMPT,
    SHORT_ANSWER_GRADING_PROMPT
)
from ..prompt_utils import inject_schema
from ..utils import load_lecture_material


class ShortAnswerGenerator(BaseGenerator):
    """단답형 문제 생성기"""
    
    def __init__(self, client, semaphore_limit: int = 5):
        super().__init__(client, semaphore_limit)
        self.model_name = "gemini-2.5-flash"
    
    async def generate(
        self,
        lecture_content: str,
        profile: TestProfile,
        count: int
    ) -> List[ShortAnswerProblem]:
        """
        단답형 문제 생성 메인 메서드
        
        Args:
            lecture_content: 강의 자료 텍스트 또는 파일 경로
            profile: 사용자 프로필
            count: 생성할 문제 수
        
        Returns:
            List[ShortAnswerProblem]: 생성된 문제 리스트
        """
        print(f"[ShortAnswer] Generating {count} problems...")
        
        # 강의 자료 로드
        lecture_material = await load_lecture_material(lecture_content, self.client)
        
        # 1. Plan Generation
        plan = await self._create_plan(lecture_material, profile, count)
        
        if not plan:
            print("[ShortAnswer] Planning failed.")
            return []
        
        print(f"[ShortAnswer] Plan created")
        
        # 2. Problem Writing
        problems = await self._write_problems(lecture_material, plan, profile, count)
        
        print(f"[ShortAnswer] Generated {len(problems)} problems")
        return problems
    
    async def _create_plan(
        self,
        lecture_content: str,
        profile: TestProfile,
        count: int
    ) -> List[Dict[str, Any]]:
        """문제 출제 계획 수립"""
        async with self.semaphore:
            # 단답형 Planner는 FiveChoicePlan과 유사한 구조를 반환
            from ..schemas import FiveChoicePlan
            
            prompt = inject_schema(
                SHORT_ANSWER_PLANNER_SYSTEM_PROMPT,
                FiveChoicePlan  # 동일한 구조 사용
            )
            
            # 강의 내용이 너무 길면 자름
            lecture_truncated = lecture_content[:10000] if isinstance(lecture_content, str) else lecture_content
            
            contents = [
                f"[Lecture Material]\n{lecture_truncated}",
                f"[User Profile]\n{profile.model_dump_json()}",
                f"[Target Count]\n{count}"
            ]
            
            try:
                response_text = await self._call_gemini_async(
                    contents=contents,
                    system_instruction=prompt,
                    response_schema=FiveChoicePlan.model_json_schema(),
                    model=self.model_name
                )
                
                data = self._parse_json_response(response_text)
                
                # planned_items 반환
                if isinstance(data, dict) and "planned_items" in data:
                    return data["planned_items"]
                elif isinstance(data, list):
                    return data
                
                return []
                
            except Exception as e:
                print(f"[ShortAnswer] Plan Error: {e}")
                import traceback
                traceback.print_exc()
                return []
    
    async def _write_problems(
        self,
        lecture_content: str,
        plan: List[Dict[str, Any]],
        profile: TestProfile,
        count: int
    ) -> List[ShortAnswerProblem]:
        """계획에 따라 문제 생성"""
        async with self.semaphore:
            # 스키마 주입
            prompt = inject_schema(
                SHORT_ANSWER_WRITER_SYSTEM_PROMPT,
                ShortAnswerResponse
            )
            
            # 강의 내용이 너무 길면 자름
            lecture_truncated = lecture_content[:15000] if isinstance(lecture_content, str) else lecture_content
            
            contents = [
                f"[Lecture Material]\n{lecture_truncated}",
                f"[Plan]\n{json.dumps(plan, ensure_ascii=False)}",
                f"[User Profile]\n{profile.model_dump_json()}",
                f"[Target Count]\n{count}"
            ]
            
            try:
                response_text = await self._call_gemini_async(
                    contents=contents,
                    system_instruction=prompt,
                    response_schema=ShortAnswerResponse.model_json_schema(),
                    model=self.model_name
                )
                
                data = self._parse_json_response(response_text)
                
                # ShortAnswerResponse 모델로 변환
                if isinstance(data, dict) and "short_answer_problems" in data:
                    problems = [
                        ShortAnswerProblem(**p)
                        for p in data["short_answer_problems"]
                    ]
                    return problems
                elif isinstance(data, list):
                    return [ShortAnswerProblem(**p) for p in data]
                
                return []
                
            except Exception as e:
                print(f"[ShortAnswer] Write Error: {e}")
                import traceback
                traceback.print_exc()
                return []
    
    async def evaluate(
        self,
        problems: List[ShortAnswerProblem],
        user_answers: List[UserAnswer],
        lecture_content: str = ""
    ) -> List[ProblemFeedback]:
        """
        단답형 문제 채점 (LLM을 사용하여 키워드 및 맥락 기반 채점)
        
        Args:
            problems: 생성되었던 문제 리스트
            user_answers: 사용자 답안 리스트
            lecture_content: 강의 자료 (피드백 생성 시 문맥 참고용)
        
        Returns:
            List[ProblemFeedback]: 채점 결과 리스트
        """
        print("[ShortAnswer] AI Grading in progress...")
        problem_map = {p.id: p for p in problems}
        
        # 비동기 병렬 채점을 위한 태스크 리스트
        tasks = []
        for ans in user_answers:
            problem = problem_map.get(ans.problem_id)
            if problem:
                tasks.append(self._grade_single_problem(problem, ans, lecture_content))
            else:
                print(f"[ShortAnswer] Warning: Problem {ans.problem_id} not found, skipping...")
        
        results = await asyncio.gather(*tasks, return_exceptions=True)
        
        # 예외 처리: 실패한 경우 기본 피드백 반환
        final_results = []
        for i, result in enumerate(results):
            if isinstance(result, Exception):
                print(f"[ShortAnswer] Grading error for answer {i}: {result}")
                # 기본 피드백 반환
                ans = user_answers[i]
                problem = problem_map.get(ans.problem_id)
                if problem:
                    final_results.append(ProblemFeedback(
                        problem_id=problem.id,
                        is_correct=False,
                        user_response=ans.user_response,
                        correct_answer=problem.best_answer,
                        explanation="채점 중 오류가 발생했습니다.",
                        score=0.0
                    ))
            else:
                final_results.append(result)
        
        print(f"[ShortAnswer] Evaluated {len(final_results)} answers")
        return final_results
    
    async def _grade_single_problem(
        self,
        problem: ShortAnswerProblem,
        answer: UserAnswer,
        context: str
    ) -> ProblemFeedback:
        """단일 문제 채점 (LLM 호출)"""
        async with self.semaphore:
            # 스키마 정의
            from pydantic import BaseModel, Field
            from typing import Literal
            
            class GradingResult(BaseModel):
                score: float = Field(..., ge=0.0, le=10.0, description="점수 (0.0 ~ 10.0)")
                feedback: str = Field(..., description="채점 피드백 (한국어)")
                is_correct: bool = Field(..., description="정답 여부 (점수 7.0 이상이면 True)")
            
            # 스키마 주입
            prompt = inject_schema(
                SHORT_ANSWER_GRADING_PROMPT,
                GradingResult
            )
            
            # 프롬프트 구성
            lecture_truncated = context[:5000] if len(context) > 5000 else context
            
            formatted_prompt = prompt.format(
                lecture_content=lecture_truncated,
                question=problem.question_content,
                correct_answer=problem.best_answer,
                evaluation_criteria=problem.evaluation_criteria,
                user_response=answer.user_response
            )
            
            contents = [formatted_prompt]
            
            try:
                response_text = await self._call_gemini_async(
                    contents=contents,
                    system_instruction="",  # 프롬프트에 이미 포함됨
                    response_schema=GradingResult.model_json_schema(),
                    model=self.model_name
                )
                
                data = self._parse_json_response(response_text)
                grading_result = GradingResult(**data)
                
                return ProblemFeedback(
                    problem_id=problem.id,
                    is_correct=grading_result.is_correct,
                    user_response=answer.user_response,
                    correct_answer=problem.best_answer,
                    explanation=grading_result.feedback,
                    score=grading_result.score
                )
                
            except Exception as e:
                print(f"[ShortAnswer] Grading error for problem {problem.id}: {e}")
                # 기본 피드백 반환
                return ProblemFeedback(
                    problem_id=problem.id,
                    is_correct=False,
                    user_response=answer.user_response,
                    correct_answer=problem.best_answer,
                    explanation="채점 중 오류가 발생했습니다.",
                    score=0.0
                )