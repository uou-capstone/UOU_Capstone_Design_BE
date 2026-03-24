"""
DebateGenerator: 토론 주제 생성기
토론 세션 초기화 데이터(주제, AI 페르소나 등)를 생성
"""
import asyncio
import json
from typing import List
from google.genai import types

from .base import BaseGenerator
from ..schemas import (
    TestProfile,
    DebateTopic,
    DebateSetupResponse,
    UserAnswer,
    ProblemFeedback
)
from ..prompts import DEBATE_TOPIC_GENERATOR_SYSTEM_PROMPT
from ..prompt_utils import inject_schema
from ..utils import load_lecture_material


class DebateGenerator(BaseGenerator):
    """토론 주제 생성기"""
    
    def __init__(self, client, semaphore_limit: int = 5):
        super().__init__(client, semaphore_limit)
        self.model_name = "gemini-2.5-flash"
    
    async def generate(
        self,
        lecture_content: str,
        profile: TestProfile,
        count: int = 1
    ) -> List[DebateTopic]:
        """
        토론 주제를 생성합니다.
        
        Args:
            lecture_content: 강의 자료 텍스트 또는 파일 경로
            profile: 사용자 프로필
            count: 생성할 토론 주제의 후보 개수 (보통 1개 또는 선택지 3개)
        
        Returns:
            List[DebateTopic]: 생성된 토론 주제 리스트
        """
        print(f"[Debate] Generating {count} debate topics...")
        
        # 강의 자료 로드
        lecture_material = await load_lecture_material(lecture_content, self.client)
        
        async with self.semaphore:
            # 스키마 주입
            prompt = inject_schema(
                DEBATE_TOPIC_GENERATOR_SYSTEM_PROMPT,
                DebateSetupResponse
            )
            
            # 토론은 문맥이 중요하므로 길게 유지
            lecture_truncated = lecture_material[:20000] if isinstance(lecture_material, str) else lecture_material
            
            contents = [
                f"[Lecture Material]\n{lecture_truncated}",
                f"[User Profile]\n{profile.model_dump_json()}",
                f"[Target Count]\n{count}"
            ]
            
            try:
                response_text = await self._call_gemini_async(
                    contents=contents,
                    system_instruction=prompt,
                    response_schema=DebateSetupResponse.model_json_schema(),
                    model=self.model_name
                )
                
                data = self._parse_json_response(response_text)
                
                # DebateSetupResponse 모델로 변환
                if isinstance(data, dict) and "topics" in data:
                    topics = [
                        DebateTopic(**t)
                        for t in data["topics"]
                    ]
                    print(f"[Debate] Generated {len(topics)} debate topics")
                    return topics
                elif isinstance(data, list):
                    # 직접 리스트로 반환된 경우
                    topics = [DebateTopic(**t) for t in data]
                    print(f"[Debate] Generated {len(topics)} debate topics")
                    return topics
                
                print("[Debate] No topics generated")
                return []
                
            except Exception as e:
                print(f"[Debate] Error: {e}")
                import traceback
                traceback.print_exc()
                return []
    
    async def evaluate(
        self,
        problems: List[DebateTopic],
        user_answers: List[UserAnswer],
        lecture_content: str = ""
    ) -> List[ProblemFeedback]:
        """
        토론 평가 (토론은 주관적이므로 기본 피드백만 제공)
        
        Args:
            problems: 생성되었던 토론 주제 리스트
            user_answers: 사용자 답안 리스트 (토론 참여 내용 등)
            lecture_content: 강의 자료
        
        Returns:
            List[ProblemFeedback]: 평가 결과 리스트
        """
        print("[Debate] Evaluating debate participation...")
        results = []
        
        # 토론은 채점보다는 참여 평가에 중점
        topic_map = {t.topic: t for t in problems}  # topic을 키로 사용
        
        for ans in user_answers:
            # 토론은 문제 ID가 아닌 주제로 매칭될 수 있음
            # 여기서는 간단히 첫 번째 토론 주제에 매칭
            if problems:
                topic = problems[0]
                explanation = f"토론 주제: {topic.topic}\n찬성 측: {topic.pro_side_stand}\n반대 측: {topic.con_side_stand}"
                
                results.append(ProblemFeedback(
                    problem_id=ans.problem_id,
                    is_correct=True,  # 토론은 참여 자체가 중요
                    user_response=str(ans.user_response),
                    correct_answer=topic.topic,
                    explanation=explanation,
                    score=10.0  # 참여 점수
                ))
        
        print(f"[Debate] Evaluated {len(results)} debate participations")
        return results