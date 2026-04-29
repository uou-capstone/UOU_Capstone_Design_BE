"""
OXProblemGenerator: OX 문제 생성기
Planner -> Writer -> Validator 흐름을 비동기로 구현
"""
import asyncio
import json
from typing import List, Dict, Any, Optional
from google.genai import types

from .base import BaseGenerator
from ..schemas import (
    TestProfile,
    OXProblem,
    OXResponse,
    OXPlan,
    ValidationResult,
    FeedbackItem,
    UserAnswer,
    ProblemFeedback
)
from ..prompts import (
    OX_PLANNER_SYSTEM_PROMPT,
    OX_WRITER_SYSTEM_PROMPT,
    OX_VALIDATOR_SYSTEM_PROMPT
)
from ..prompt_utils import inject_schema
from ..utils import load_lecture_material


class OXProblemGenerator(BaseGenerator):
    """OX 문제 생성기"""
    
    def __init__(self, client, semaphore_limit: int = 5):
        super().__init__(client, semaphore_limit)
        self.model_name = "gemini-2.5-flash"
    
    async def generate(
        self,
        lecture_content: str,
        profile: TestProfile,
        count: int
    ) -> List[OXProblem]:
        """
        OX 문제 생성 메인 메서드
        
        Args:
            lecture_content: 강의 자료 텍스트 또는 파일 경로
            profile: 사용자 프로필
            count: 생성할 문제 수
        
        Returns:
            List[OXProblem]: 생성된 문제 리스트
        """
        print(f"[OX] Starting generation for {count} problems...")
        
        # 강의 자료 로드
        lecture_material = await load_lecture_material(lecture_content, self.client)
        
        # 1. Plan Generation
        plan = await self._create_plan(lecture_material, profile, count)
        if not plan or not plan.get("planned_items"):
            print("[OX] Planning failed.")
            return []
        
        print(f"[OX] Plan created: {len(plan.get('planned_items', []))} items")
        
        # 2. Problem Writing
        problems = await self._write_problems(lecture_material, plan, profile, count)
        
        if not problems:
            print("[OX] Writing failed.")
            return []
        
        print(f"[OX] Generated {len(problems)} problems")
        
        # 3. Validation & Refinement
        final_problems = await self._validate_and_fix(
            lecture_material,
            problems,
            profile,
            count,
            max_retries=2
        )
        
        print(f"[OX] Final: {len(final_problems)} problems validated")
        return final_problems
    
    async def _create_plan(
        self,
        lecture_content: str,
        profile: TestProfile,
        count: int
    ) -> Dict[str, Any]:
        """문제 출제 계획 수립"""
        async with self.semaphore:
            # 스키마 주입
            prompt = inject_schema(
                OX_PLANNER_SYSTEM_PROMPT,
                OXPlan
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
                    response_schema=OXPlan.model_json_schema(),
                    model=self.model_name
                )
                
                plan_data = self._parse_json_response(response_text)
                return plan_data
                
            except Exception as e:
                print(f"[OX] Plan Error: {e}")
                import traceback
                traceback.print_exc()
                return {}
    
    async def _write_problems(
        self,
        lecture_content: str,
        plan: Dict[str, Any],
        profile: TestProfile,
        count: int
    ) -> List[OXProblem]:
        """계획에 따라 문제 생성"""
        async with self.semaphore:
            # 스키마 주입
            prompt = inject_schema(
                OX_WRITER_SYSTEM_PROMPT,
                OXResponse
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
                    response_schema=OXResponse.model_json_schema(),
                    model=self.model_name
                )
                
                data = self._parse_json_response(response_text)
                
                # OXResponse 모델로 변환
                if isinstance(data, dict) and "ox_problems" in data:
                    problems = [
                        OXProblem(**p)
                        for p in data["ox_problems"]
                    ]
                    return problems
                elif isinstance(data, list):
                    return [OXProblem(**p) for p in data]
                
                return []
                
            except Exception as e:
                print(f"[OX] Write Error: {e}")
                import traceback
                traceback.print_exc()
                return []
    
    async def _validate_and_fix(
        self,
        lecture_content: str,
        problems: List[OXProblem],
        profile: TestProfile,
        required_count: int,
        max_retries: int = 2
    ) -> List[OXProblem]:
        """생성된 문제 검증 및 수정"""
        current_problems = problems
        
        for attempt in range(max_retries):
            # 검증 수행
            validation = await self._validate(
                lecture_content,
                current_problems,
                profile,
                required_count
            )
            
            if validation.is_valid:
                print(f"[OX] Validation passed on attempt {attempt + 1}")
                return current_problems
            
            # 검증 실패 시 피드백 수집
            feedback_messages = validation.feedback_message or []
            if feedback_messages:
                feedback_text = "\n".join([
                    f"Problem {item.id}: {item.message}"
                    for item in feedback_messages
                ])
                print(f"[OX] Validation failed: {feedback_text[:200]}...")
            
            # 최대 재시도 횟수 초과 시 현재 결과 반환
            if attempt >= max_retries - 1:
                break
        
        print(f"[OX] Max retries reached. Returning {len(current_problems)} problems.")
        return current_problems
    
    async def _validate(
        self,
        lecture_content: str,
        problems: List[OXProblem],
        profile: TestProfile,
        required_count: int
    ) -> ValidationResult:
        """문제 검증 수행"""
        async with self.semaphore:
            # 스키마 주입
            prompt = inject_schema(
                OX_VALIDATOR_SYSTEM_PROMPT,
                ValidationResult
            )
            
            # 강의 내용이 너무 길면 자름
            lecture_truncated = lecture_content[:10000] if isinstance(lecture_content, str) else lecture_content
            
            problems_json = [p.model_dump() for p in problems]
            
            contents = [
                f"[Lecture Material]\n{lecture_truncated}",
                f"[Generated Problems]\n{json.dumps(problems_json, ensure_ascii=False)}",
                f"[User Profile]\n{profile.model_dump_json()}",
                f"[Required Count]\n{required_count}"
            ]
            
            try:
                response_text = await self._call_gemini_async(
                    contents=contents,
                    system_instruction=prompt,
                    response_schema=ValidationResult.model_json_schema(),
                    model=self.model_name
                )
                
                data = self._parse_json_response(response_text)
                return ValidationResult(**data)
                
            except Exception as e:
                print(f"[OX] Validation Error: {e}")
                # 검증 실패로 간주
                return ValidationResult(
                    is_valid=False,
                    reasoning=f"Validation error: {str(e)}",
                    feedback_message=[FeedbackItem(id=0, message=str(e))]
                )
    
    async def evaluate(
        self,
        problems: List[OXProblem],
        user_answers: List[UserAnswer],
        lecture_content: str = ""
    ) -> List[ProblemFeedback]:
        """
        OX 문제 채점 (객관식은 로직으로 빠르게 채점)
        
        Args:
            problems: 생성되었던 문제 리스트
            user_answers: 사용자 답안 리스트
            lecture_content: 강의 자료 (사용하지 않지만 인터페이스 일관성 유지)
        
        Returns:
            List[ProblemFeedback]: 채점 결과 리스트
        """
        print("[OX] Evaluating answers...")
        results = []
        
        # O(N) 매핑을 위해 딕셔너리로 변환
        problem_map = {p.id: p for p in problems}
        
        for ans in user_answers:
            problem = problem_map.get(ans.problem_id)
            if not problem:
                print(f"[OX] Warning: Problem {ans.problem_id} not found, skipping...")
                continue
            
            # 정답 비교 (대소문자 무시)
            user_answer_upper = str(ans.user_response).strip().upper()
            correct_answer_upper = str(problem.correct_answer).strip().upper()
            is_correct = (user_answer_upper == correct_answer_upper)
            
            # 해설 가져오기
            explanation = problem.explanation if problem.explanation else f"정답은 {problem.correct_answer}입니다."
            
            results.append(ProblemFeedback(
                problem_id=problem.id,
                is_correct=is_correct,
                user_response=str(ans.user_response),
                correct_answer=str(problem.correct_answer),
                explanation=explanation,
                score=10.0 if is_correct else 0.0
            ))
        
        print(f"[OX] Evaluated {len(results)} answers")
        return results