"""
BaseGenerator: 모든 Generator의 추상 기본 클래스
공통 인터페이스와 유틸리티 메서드 제공
"""
from abc import ABC, abstractmethod
from typing import List, Any, Optional
import asyncio
import json
import re
from google import genai
from ..schemas import TestProfile


class BaseGenerator(ABC):
    """모든 문제 생성기의 기본 클래스"""
    
    def __init__(self, client: genai.Client, semaphore_limit: int = 5):
        """
        Args:
            client: Gemini Client 인스턴스
            semaphore_limit: 동시 요청 제한 수 (Rate Limit 방어)
        """
        self.client = client
        # 동시 요청 제한 (Rate Limit 방어, 필요시 조절)
        self.semaphore = asyncio.Semaphore(semaphore_limit)
    
    @abstractmethod
    async def generate(
        self,
        lecture_content: str,
        profile: TestProfile,
        count: int
    ) -> List[Any]:
        """
        강의 내용과 프로필을 바탕으로 문제를 생성합니다.
        
        Args:
            lecture_content: 강의 자료 텍스트 또는 파일 경로
            profile: 사용자 프로필
            count: 생성할 문제 수
        
        Returns:
            List[Any]: 생성된 문제 리스트 (구체적인 타입은 하위 클래스에서 결정)
        """
        pass
    
    @abstractmethod
    async def evaluate(
        self,
        problems: List[Any],
        user_answers: List[Any],
        lecture_content: str = ""
    ) -> List[Any]:
        """
        문제와 사용자 답안을 받아 채점 결과(리스트)를 반환합니다.
        
        Args:
            problems: 생성되었던 문제 리스트
            user_answers: 사용자 답안 리스트
            lecture_content: 강의 자료 (피드백 생성 시 문맥 참고용)
        
        Returns:
            List[Any]: 채점 결과 리스트 (구체적인 타입은 하위 클래스에서 결정)
        """
        pass
    
    def _parse_json_response(self, response_text: str) -> Any:
        """
        LLM 응답에서 JSON만 추출하여 파싱하는 헬퍼 메서드
        
        Args:
            response_text: LLM 응답 텍스트
        
        Returns:
            Any: 파싱된 JSON 객체 (실패 시 빈 딕셔너리)
        """
        try:
            # Markdown 코드 블록 제거 (```json ... ```)
            cleaned_text = re.sub(r"```json\s*", "", response_text)
            cleaned_text = re.sub(r"```\s*$", "", cleaned_text)
            cleaned_text = cleaned_text.strip()
            return json.loads(cleaned_text)
        except json.JSONDecodeError as e:
            print(f"[JSON Parsing Error] {e}\nOriginal Text: {response_text[:500]}...")
            return {}  # 실패 시 빈 딕셔너리 반환
    
    async def _call_gemini_async(
        self,
        contents: List[str],
        system_instruction: str,
        response_schema: Optional[dict] = None,
        model: str = "gemini-2.5-flash"
    ) -> str:
        """
        Gemini API를 비동기로 호출하는 공통 메서드
        
        Args:
            contents: 프롬프트 내용 리스트
            system_instruction: 시스템 프롬프트
            response_schema: JSON 스키마 (없으면 자유 형식)
            model: 사용할 모델명
        
        Returns:
            str: 응답 텍스트
        """
        from google.genai import types
        
        config = types.GenerateContentConfig(
            system_instruction=system_instruction,
            response_mime_type="application/json" if response_schema else "text/plain"
        )
        
        if response_schema:
            config.response_schema = response_schema
        
        # 비동기 호출 (I/O Bound 작업)
        response = await asyncio.to_thread(
            self.client.models.generate_content,
            model=model,
            contents=contents,
            config=config
        )
        
        return response.text
