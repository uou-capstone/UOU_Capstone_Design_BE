"""
LectureTestGenerator: 통합 테스트 생성 오케스트레이터
외부에서 호출할 단일 진입점 클래스
"""
import os
import asyncio
from typing import Optional, Dict, Any, Union, List

from google import genai

from .schemas import (
    ProblemRequest,
    TestGenerationResponse,
    TestProfile,
    ExamType,
    FiveChoiceProblem,
    OXProblem,
    FlashCard,
    ShortAnswerProblem,
    DebateTopic,
    FiveChoiceResponse,
    OXResponse,
    FlashCardResponse,
    ShortAnswerResponse,
    GradingRequest,
    GradingResponse,
    UserAnswer,
    ProblemFeedback
)
from .profile import generate_profile_async
from .utils import get_gemini_client
from .prompts import OVERALL_FEEDBACK_PROMPT

# Generators
from .generators import (
    FiveChoiceGenerator,
    OXProblemGenerator,
    FlashCardGenerator,
    ShortAnswerGenerator,
    DebateGenerator
)


class LectureTestGenerator:
    """
    통합 테스트 생성기
    
    사용 예시:
        generator = LectureTestGenerator()
        request = ProblemRequest(
            exam_type=ExamType.FIVE_CHOICE,
            target_count=10,
            lecture_content="강의 자료 텍스트..."
        )
        result = await generator.generate_test(request)
    """
    
    def __init__(self, api_key: Optional[str] = None):
        """
        Args:
            api_key: Gemini API 키 (없으면 환경 변수에서 가져옴)
        """
        # 1. 클라이언트 초기화
        self.client = get_gemini_client(api_key)
        
        # 2. Generator 초기화 (Factory Pattern)
        self.generators: Dict[ExamType, Union[
            FiveChoiceGenerator,
            OXProblemGenerator,
            FlashCardGenerator,
            ShortAnswerGenerator,
            DebateGenerator
        ]] = {
            ExamType.FIVE_CHOICE: FiveChoiceGenerator(self.client),
            ExamType.OX_PROBLEM: OXProblemGenerator(self.client),
            ExamType.FLASH_CARD: FlashCardGenerator(self.client),
            ExamType.SHORT_ANSWER: ShortAnswerGenerator(self.client),
            ExamType.DEBATE: DebateGenerator(self.client),
        }
    
    async def generate_test(self, request: ProblemRequest) -> TestGenerationResponse:
        """
        통합 테스트 생성 메서드
        
        Args:
            request: 문제 생성 요청
        
        Returns:
            TestGenerationResponse: 생성된 테스트 응답
        
        Raises:
            ValueError: 지원하지 않는 시험 유형인 경우
        """
        print(f"=== [TestGenerator] Starting Process: {request.exam_type.value} ===")
        
        # 1. User Profile 확보 (없으면 자동 생성)
        current_profile = request.user_profile
        if not current_profile:
            current_profile = await generate_profile_async(
                request.lecture_content,
                self.client
            )
            print(">> User Profile Auto-Generated.")
        else:
            print(">> Using provided User Profile.")
        
        # 2. Generator 선택
        generator = self.generators.get(request.exam_type)
        if not generator:
            raise ValueError(f"Unsupported exam type: {request.exam_type}")
        
        # 3. 문제 생성 실행 (비동기)
        # DebateGenerator는 count 인자의 의미가 약간 다를 수 있음(주제 개수 등)
        generated_content = await generator.generate(
            lecture_content=request.lecture_content,
            profile=current_profile,
            count=request.target_count
        )
        
        print(f"=== [TestGenerator] Finished: {len(generated_content)} items generated ===")
        
        # 4. 통합 응답 구성
        # problems 필드는 Union 타입이므로, 각 시험 유형에 맞는 Response 객체로 래핑
        problems_wrapped = self._wrap_problems(request.exam_type, generated_content)
        
        return TestGenerationResponse(
            exam_type=request.exam_type,
            user_profile=current_profile,
            problems=problems_wrapped,
            metadata={
                "source_length": len(request.lecture_content),
                "generated_count": len(generated_content),
                "exam_type": request.exam_type.value
            }
        )
    
    def _wrap_problems(
        self,
        exam_type: ExamType,
        problems: List[Any]
    ) -> Union[
        FiveChoiceResponse,
        OXResponse,
        FlashCardResponse,
        ShortAnswerResponse,
        DebateTopic
    ]:
        """
        생성된 문제 리스트를 각 시험 유형에 맞는 Response 객체로 래핑
        
        Args:
            exam_type: 시험 유형
            problems: 생성된 문제 리스트
        
        Returns:
            각 시험 유형에 맞는 Response 객체
        """
        if exam_type == ExamType.FIVE_CHOICE:
            return FiveChoiceResponse(mcq_problems=problems)
        elif exam_type == ExamType.OX_PROBLEM:
            return OXResponse(ox_problems=problems)
        elif exam_type == ExamType.FLASH_CARD:
            return FlashCardResponse(flash_cards=problems)
        elif exam_type == ExamType.SHORT_ANSWER:
            return ShortAnswerResponse(short_answer_problems=problems)
        elif exam_type == ExamType.DEBATE:
            # Debate는 리스트가 아니라 단일 객체일 수 있음
            if problems and len(problems) > 0:
                return problems[0]  # 첫 번째 토론 주제 반환
            else:
                # 빈 경우 기본값 반환
                return DebateTopic(
                    topic="No topic generated",
                    context="",
                    pro_side_stand="",
                    con_side_stand="",
                    evaluation_criteria=[]
                )
        else:
            raise ValueError(f"Unsupported exam type for wrapping: {exam_type}")
    
    async def evaluate_test(self, request: GradingRequest) -> GradingResponse:
        """
        제출된 답안을 채점하고 피드백을 생성합니다.
        
        Args:
            request: 채점 요청
        
        Returns:
            GradingResponse: 채점 결과 및 피드백
        
        Raises:
            ValueError: 지원하지 않는 시험 유형인 경우
        """
        print(f"=== [TestGenerator] Starting Evaluation: {request.exam_type.value} ===")
        
        # 1. Generator 선택
        generator = self.generators.get(request.exam_type)
        if not generator:
            raise ValueError(f"Unsupported exam type: {request.exam_type}")
        
        # 2. 문제를 Pydantic 모델로 변환 (필요한 경우)
        # request.problems는 딕셔너리 리스트일 수 있으므로 각 Generator의 evaluate에서 처리
        # 여기서는 그대로 넘김
        
        # 3. 각 문제별 채점 (Delegate)
        problem_results = await generator.evaluate(
            request.problems,
            request.user_answers,
            request.lecture_content
        )
        
        print(f"=== [TestGenerator] Evaluated {len(problem_results)} problems ===")
        
        # 4. 총점 계산
        total_score = sum(r.score for r in problem_results)
        max_score = len(problem_results) * 10.0  # 문제당 10점 가정
        
        # 5. 전체 피드백 생성 (종합 분석)
        overall_feedback = await self._generate_overall_feedback(
            problem_results,
            total_score,
            max_score,
            request.lecture_content
        )
        
        return GradingResponse(
            total_score=total_score,
            max_score=max_score,
            results=problem_results,
            overall_feedback=overall_feedback
        )
    
    async def _generate_overall_feedback(
        self,
        results: List[ProblemFeedback],
        total_score: float,
        max_score: float,
        context: str
    ) -> str:
        """
        전체 피드백 생성 (AI 생성)
        
        Args:
            results: 문제별 채점 결과
            total_score: 총점
            max_score: 만점
            context: 강의 자료
        
        Returns:
            str: 전체 피드백 텍스트
        """
        # 틀린 문제 위주로 요약 정보 생성
        correct_count = sum(1 for r in results if r.is_correct)
        wrong_count = len(results) - correct_count
        
        summary_lines = []
        for r in results:
            status = "정답" if r.is_correct else "오답"
            summary_lines.append(f"Q{r.problem_id}: {status} (점수: {r.score}/10.0)")
        
        summary = "\n".join(summary_lines)
        accuracy_percent = (total_score / max_score * 100) if max_score > 0 else 0.0
        
        # 프롬프트 구성
        lecture_truncated = context[:10000] if len(context) > 10000 else context
        
        formatted_prompt = OVERALL_FEEDBACK_PROMPT.format(
            total_score=int(total_score),
            max_score=int(max_score),
            accuracy_percent=round(accuracy_percent, 1),
            results_summary=summary,
            lecture_content=lecture_truncated
        )
        
        contents = [formatted_prompt]
        
        try:
            response_text = await asyncio.to_thread(
                self.client.models.generate_content,
                model="gemini-2.5-flash",
                contents=contents,
                config=None  # 텍스트 출력이므로 스키마 불필요
            )
            return response_text.text
        except Exception as e:
            print(f"[TestGenerator] Feedback generation error: {e}")
            return f"총점: {int(total_score)}/{int(max_score)}점 (정답률: {round(accuracy_percent, 1)}%)\n\n틀린 문제를 다시 확인하고 관련 강의 내용을 복습해주세요."


# 간편한 테스트를 위한 실행 블록
if __name__ == "__main__":
    async def main():
        # 테스트용 더미 데이터
        dummy_content = """
        # Python Programming
        
        Python is a versatile programming language that is widely used for web development,
        data science, artificial intelligence, and automation.
        
        ## Key Concepts
        
        ### Variables
        Variables in Python are dynamically typed. You don't need to declare the type.
        
        ### Functions
        Functions are defined using the `def` keyword. They can take parameters and return values.
        
        ### Classes
        Python supports object-oriented programming through classes and inheritance.
        """
        
        req = ProblemRequest(
            exam_type=ExamType.FIVE_CHOICE,
            target_count=3,
            lecture_content=dummy_content
        )
        
        agent = LectureTestGenerator()
        result = await agent.generate_test(req)
        
        print("\n[Result Summary]")
        print(f"Type: {result.exam_type.value}")
        print(f"Profile Level: {result.user_profile.user_status.proficiency_level.value}")
        
        # 문제 개수 확인
        if isinstance(result.problems, FiveChoiceResponse):
            print(f"Problems: {len(result.problems.mcq_problems)}")
        elif isinstance(result.problems, OXResponse):
            print(f"Problems: {len(result.problems.ox_problems)}")
        elif isinstance(result.problems, FlashCardResponse):
            print(f"Cards: {len(result.problems.flash_cards)}")
        elif isinstance(result.problems, ShortAnswerResponse):
            print(f"Problems: {len(result.problems.short_answer_problems)}")
        elif isinstance(result.problems, DebateTopic):
            print(f"Debate Topic: {result.problems.topic}")
        
        # JSON 출력 (일부만)
        print("\n[Response JSON (excerpt)]")
        result_json = result.model_dump_json(indent=2)
        # 너무 길면 앞부분만 출력
        if len(result_json) > 1000:
            print(result_json[:1000] + "...")
        else:
            print(result_json)
    
    # Windows 호환성
    if os.name == 'nt':
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    
    asyncio.run(main())
