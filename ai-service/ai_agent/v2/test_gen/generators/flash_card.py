"""
FlashCardGenerator: 플래시카드 생성기
Concept Extraction -> Card Generation 흐름을 비동기로 구현
"""
import asyncio
import json
from typing import List, Dict, Any
from google.genai import types

from .base import BaseGenerator
from ..schemas import (
    TestProfile,
    FlashCard,
    FlashCardResponse,
    UserAnswer,
    ProblemFeedback
)
from ..prompts import (
    FLASH_CARD_CONCEPT_PLANNER_SYSTEM_PROMPT,
    FLASH_CARD_WRITER_SYSTEM_PROMPT,
    FLASH_CARD_QUALITY_VALIDATOR_SYSTEM_PROMPT
)
from ..prompt_utils import inject_schema
from ..utils import load_lecture_material


class FlashCardGenerator(BaseGenerator):
    """플래시카드 생성기"""
    
    def __init__(self, client, semaphore_limit: int = 5):
        super().__init__(client, semaphore_limit)
        self.model_name = "gemini-2.5-flash"
    
    async def generate(
        self,
        lecture_content: str,
        profile: TestProfile,
        count: int
    ) -> List[FlashCard]:
        """
        플래시카드 생성 메인 메서드
        
        Args:
            lecture_content: 강의 자료 텍스트 또는 파일 경로
            profile: 사용자 프로필
            count: 생성할 카드 수
        
        Returns:
            List[FlashCard]: 생성된 플래시카드 리스트
        """
        print(f"[FlashCard] Generating {count} cards...")
        
        # 강의 자료 로드
        lecture_material = await load_lecture_material(lecture_content, self.client)
        
        # 1. Key Concepts Extraction (Plan)
        concepts = await self._extract_concepts(lecture_material, profile, count)
        
        if not concepts:
            print("[FlashCard] Concept extraction failed.")
            return []
        
        print(f"[FlashCard] Extracted {len(concepts)} concepts")
        
        # 2. Card Generation
        cards = await self._create_cards(lecture_material, concepts, profile)
        
        print(f"[FlashCard] Generated {len(cards)} cards")
        return cards
    
    async def _extract_concepts(
        self,
        lecture_content: str,
        profile: TestProfile,
        count: int
    ) -> List[Dict[str, Any]]:
        """핵심 개념 추출 (Planner 역할)"""
        async with self.semaphore:
            # Concept Planner는 FiveChoicePlan과 유사한 구조를 반환
            from ..schemas import FiveChoicePlan
            
            prompt = inject_schema(
                FLASH_CARD_CONCEPT_PLANNER_SYSTEM_PROMPT,
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
                return []
                
            except Exception as e:
                print(f"[FlashCard] Concept Extraction Error: {e}")
                import traceback
                traceback.print_exc()
                return []
    
    async def _create_cards(
        self,
        lecture_content: str,
        concepts: List[Dict[str, Any]],
        profile: TestProfile
    ) -> List[FlashCard]:
        """개념 리스트를 바탕으로 플래시카드 생성"""
        async with self.semaphore:
            # 스키마 주입
            prompt = inject_schema(
                FLASH_CARD_WRITER_SYSTEM_PROMPT,
                FlashCardResponse
            )
            
            # 강의 내용이 너무 길면 자름
            lecture_truncated = lecture_content[:15000] if isinstance(lecture_content, str) else lecture_content
            
            contents = [
                f"[Lecture Material]\n{lecture_truncated}",
                f"[Concepts]\n{json.dumps(concepts, ensure_ascii=False)}",
                f"[User Profile]\n{profile.model_dump_json()}"
            ]
            
            try:
                response_text = await self._call_gemini_async(
                    contents=contents,
                    system_instruction=prompt,
                    response_schema=FlashCardResponse.model_json_schema(),
                    model=self.model_name
                )
                
                data = self._parse_json_response(response_text)
                
                # FlashCardResponse 모델로 변환
                if isinstance(data, dict) and "flash_cards" in data:
                    cards = [
                        FlashCard(**c)
                        for c in data["flash_cards"]
                    ]
                    return cards
                elif isinstance(data, list):
                    return [FlashCard(**c) for c in data]
                
                return []
                
            except Exception as e:
                print(f"[FlashCard] Card Generation Error: {e}")
                import traceback
                traceback.print_exc()
                return []
    
    async def evaluate(
        self,
        problems: List[FlashCard],
        user_answers: List[UserAnswer],
        lecture_content: str = ""
    ) -> List[ProblemFeedback]:
        """
        플래시카드 평가 (사용자가 답을 맞췄는지 확인)
        플래시카드는 학습 도구이므로 채점보다는 정답 확인에 중점
        
        Args:
            problems: 생성되었던 플래시카드 리스트
            user_answers: 사용자 답안 리스트
            lecture_content: 강의 자료
        
        Returns:
            List[ProblemFeedback]: 평가 결과 리스트
        """
        print("[FlashCard] Evaluating answers...")
        results = []
        
        # O(N) 매핑을 위해 딕셔너리로 변환
        card_map = {c.id: c for c in problems}
        
        for ans in user_answers:
            card = card_map.get(ans.problem_id)
            if not card:
                print(f"[FlashCard] Warning: Card {ans.problem_id} not found, skipping...")
                continue
            
            # 답안 비교 (대소문자 무시, 공백 제거)
            user_answer_clean = str(ans.user_response).strip().lower()
            correct_answer_clean = str(card.back_content).strip().lower()
            
            # 키워드 기반 부분 일치 확인 (간단한 구현)
            is_correct = user_answer_clean == correct_answer_clean or \
                        any(keyword in user_answer_clean for keyword in correct_answer_clean.split()[:3])
            
            explanation = f"정답: {card.back_content}"
            
            results.append(ProblemFeedback(
                problem_id=card.id,
                is_correct=is_correct,
                user_response=str(ans.user_response),
                correct_answer=card.back_content,
                explanation=explanation,
                score=10.0 if is_correct else 5.0  # 플래시카드는 부분 점수 부여
            ))
        
        print(f"[FlashCard] Evaluated {len(results)} answers")
        return results