"""
FiveChoiceGenerator: 5지선다 문제 생성기
Planner -> Writer -> Validator 흐름을 비동기로 구현
"""
import asyncio
import json
from typing import List, Dict, Any, Optional
from google.genai import types

from .base import BaseGenerator
from ..schemas import (
    TestProfile,
    FiveChoiceProblem,
    FiveChoiceResponse,
    FiveChoicePlan,
    ValidationResult,
    FeedbackItem,
    UserAnswer,
    ProblemFeedback
)
from ..prompts import (
    FIVE_CHOICE_PLANNER_SYSTEM_PROMPT,
    FIVE_CHOICE_WRITER_SYSTEM_PROMPT,
    FIVE_CHOICE_VALIDATOR_SYSTEM_PROMPT
)
from ..prompt_utils import inject_schema
from ..utils import load_lecture_material


class FiveChoiceGenerator(BaseGenerator):
    """5지선다 문제 생성기"""
    
    def __init__(self, client, semaphore_limit: int = 5):
        super().__init__(client, semaphore_limit)
        self.model_name = "gemini-2.5-flash"
    
    async def generate(
        self,
        lecture_content: str,
        profile: TestProfile,
        count: int
    ) -> List[FiveChoiceProblem]:
        """
        5지선다 문제 생성 메인 메서드
        
        Args:
            lecture_content: 강의 자료 텍스트 또는 파일 경로
            profile: 사용자 프로필
            count: 생성할 문제 수
        
        Returns:
            List[FiveChoiceProblem]: 생성된 문제 리스트
        """
        print(f"[FiveChoice] Starting generation for {count} problems...")
        
        # 강의 자료 로드 (파일 경로인 경우)
        lecture_material = await load_lecture_material(lecture_content, self.client)
        
        # 1. Plan Generation (출제 계획 수립)
        plan = await self._create_plan(lecture_material, profile, count)
        if not plan or not plan.get("planned_items"):
            print("[FiveChoice] Planning failed.")
            return []
        
        print(f"[FiveChoice] Plan created: {len(plan.get('planned_items', []))} items")
        
        # 2. Problem Writing (문제 생성)
        problems = await self._write_problems(lecture_material, plan, profile, count)
        
        if not problems:
            print("[FiveChoice] Writing failed.")
            return []
        
        print(f"[FiveChoice] Generated {len(problems)} problems")
        
        # 3. Validation & Refinement (검증 및 수정)
        final_problems = await self._validate_and_fix(
            lecture_material,
            problems,
            profile,
            count,
            max_retries=3
        )
        
        print(f"[FiveChoice] Final: {len(final_problems)} problems validated")
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
                FIVE_CHOICE_PLANNER_SYSTEM_PROMPT,
                FiveChoicePlan
            )
            
            # 강의 내용이 너무 길면 자름 (토큰 절약)
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
                
                plan_data = self._parse_json_response(response_text)
                return plan_data
                
            except Exception as e:
                print(f"[FiveChoice] Plan Error: {e}")
                import traceback
                traceback.print_exc()
                return {}
    
    async def _write_problems(
        self,
        lecture_content: str,
        plan: Dict[str, Any],
        profile: TestProfile,
        count: int,
        feedback: Optional[str] = None,
        prior_content: Optional[List[FiveChoiceProblem]] = None
    ) -> List[FiveChoiceProblem]:
        """계획에 따라 문제 생성"""
        async with self.semaphore:
            # 스키마 주입
            prompt = inject_schema(
                FIVE_CHOICE_WRITER_SYSTEM_PROMPT,
                FiveChoiceResponse
            )
            
            # 강의 내용이 너무 길면 자름
            lecture_truncated = lecture_content[:15000] if isinstance(lecture_content, str) else lecture_content
            
            contents = [
                f"[Lecture Material]\n{lecture_truncated}",
                f"[Plan]\n{json.dumps(plan, ensure_ascii=False)}",
                f"[User Profile]\n{profile.model_dump_json()}",
                f"[Target Count]\n{count}"
            ]
            
            if feedback:
                contents.append(f"[Feedback]\n{feedback}")
            
            if prior_content:
                prior_json = [p.model_dump() for p in prior_content]
                contents.append(f"[Prior Content]\n{json.dumps(prior_json, ensure_ascii=False)}")
            
            try:
                response_text = await self._call_gemini_async(
                    contents=contents,
                    system_instruction=prompt,
                    response_schema=FiveChoiceResponse.model_json_schema(),
                    model=self.model_name
                )
                
                data = self._parse_json_response(response_text)
                
                # FiveChoiceResponse 모델로 변환
                if isinstance(data, dict) and "mcq_problems" in data:
                    problems = [
                        FiveChoiceProblem(**p)
                        for p in data["mcq_problems"]
                    ]
                    return problems
                elif isinstance(data, list):
                    # 직접 리스트로 반환된 경우
                    return [FiveChoiceProblem(**p) for p in data]
                
                return []
                
            except Exception as e:
                print(f"[FiveChoice] Write Error: {e}")
                import traceback
                traceback.print_exc()
                return []
    
    async def _validate_and_fix(
        self,
        lecture_content: str,
        problems: List[FiveChoiceProblem],
        profile: TestProfile,
        required_count: int,
        max_retries: int = 3
    ) -> List[FiveChoiceProblem]:
        """
        생성된 문제 검증 및 수정 (재시도 루프 포함)
        
        Args:
            lecture_content: 강의 자료
            problems: 생성된 문제 리스트
            profile: 사용자 프로필
            required_count: 요구된 문제 수
            max_retries: 최대 재시도 횟수
        
        Returns:
            List[FiveChoiceProblem]: 검증 통과한 문제 리스트
        """
        current_problems = problems
        current_feedback = None
        
        for attempt in range(max_retries):
            # 검증 수행
            validation = await self._validate(
                lecture_content,
                current_problems,
                profile,
                required_count
            )
            
            if validation.is_valid:
                print(f"[FiveChoice] Validation passed on attempt {attempt + 1}")
                return current_problems
            
            # 검증 실패 시 피드백 수집
            feedback_messages = validation.feedback_message or []
            if feedback_messages:
                feedback_text = "\n".join([
                    f"Problem {item.id}: {item.message}"
                    for item in feedback_messages
                ])
                current_feedback = feedback_text
                print(f"[FiveChoice] Validation failed: {feedback_text[:200]}...")
            else:
                current_feedback = validation.reasoning or "Unknown validation error"
            
            # 피드백을 반영하여 재생성
            if attempt < max_retries - 1:
                print(f"[FiveChoice] Retrying with feedback (attempt {attempt + 2}/{max_retries})...")
                # Plan은 재사용하고 Writer만 다시 호출
                plan_data = {
                    "planning_strategy": "Re-generation based on feedback",
                    "planned_items": [{"id": i+1} for i in range(len(current_problems))]
                }
                
                current_problems = await self._write_problems(
                    lecture_content,
                    plan_data,
                    profile,
                    required_count,
                    feedback=current_feedback,
                    prior_content=current_problems
                )
        
        # 최대 재시도 횟수 초과 시 현재까지의 결과 반환
        print(f"[FiveChoice] Max retries reached. Returning {len(current_problems)} problems.")
        return current_problems
    
    async def _validate(
        self,
        lecture_content: str,
        problems: List[FiveChoiceProblem],
        profile: TestProfile,
        required_count: int
    ) -> ValidationResult:
        """문제 검증 수행"""
        async with self.semaphore:
            # 스키마 주입
            prompt = inject_schema(
                FIVE_CHOICE_VALIDATOR_SYSTEM_PROMPT,
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
                print(f"[FiveChoice] Validation Error: {e}")
                # 검증 실패로 간주
                return ValidationResult(
                    is_valid=False,
                    reasoning=f"Validation error: {str(e)}",
                    feedback_message=[FeedbackItem(id=0, message=str(e))]
                )
    
    async def evaluate(
        self,
        problems: List[FiveChoiceProblem],
        user_answers: List[UserAnswer],
        lecture_content: str = ""
    ) -> List[ProblemFeedback]:
        """
        5지선다 문제 채점 (객관식은 로직으로 빠르게 채점)
        
        Args:
            problems: 생성되었던 문제 리스트
            user_answers: 사용자 답안 리스트
            lecture_content: 강의 자료 (사용하지 않지만 인터페이스 일관성 유지)
        
        Returns:
            List[ProblemFeedback]: 채점 결과 리스트
        """
        print("[FiveChoice] Evaluating answers...")
        results = []
        
        # O(N) 매핑을 위해 딕셔너리로 변환
        problem_map = {p.id: p for p in problems}
        
        for ans in user_answers:
            problem = problem_map.get(ans.problem_id)
            if not problem:
                print(f"[FiveChoice] Warning: Problem {ans.problem_id} not found, skipping...")
                continue
            
            # 정답 비교 (문자열 "1" vs "1" 등)
            is_correct = (str(ans.user_response).strip() == str(problem.correct_answer).strip())
            
            # 해설 가져오기 (문제 생성 시 만들어둔 해설 활용)
            explanation = ""
            if hasattr(problem, 'intent_diagnosis') and problem.intent_diagnosis:
                explanation = problem.intent_diagnosis
            elif hasattr(problem, 'explanation') and problem.explanation:
                explanation = problem.explanation
            else:
                # 해설이 없으면 정답 선택지의 intent 사용
                correct_option = next(
                    (opt for opt in problem.options if opt.id == problem.correct_answer),
                    None
                )
                if correct_option and hasattr(correct_option, 'intent'):
                    explanation = correct_option.intent
                else:
                    explanation = f"정답은 {problem.correct_answer}번입니다."
            
            results.append(ProblemFeedback(
                problem_id=problem.id,
                is_correct=is_correct,
                user_response=str(ans.user_response),
                correct_answer=str(problem.correct_answer),
                explanation=explanation,
                score=10.0 if is_correct else 0.0
            ))
        
        print(f"[FiveChoice] Evaluated {len(results)} answers")
        return results