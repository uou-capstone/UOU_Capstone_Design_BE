"""
LectureTestGenerator 공통 유틸리티
PDF/텍스트 파일 로드, 환경 변수 관리 등
"""
import os
import asyncio
import pathlib
from typing import Union
from dotenv import load_dotenv
from google import genai
from google.genai import types


# 환경 변수 로드
load_dotenv()
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")

if not GEMINI_API_KEY:
    # 상위 디렉토리에서 .env 찾기
    base_path = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    env_path = os.path.join(base_path, '.env')
    load_dotenv(env_path)
    GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")


async def load_lecture_material(file_path_or_content: str, client: genai.Client) -> Union[str, types.File]:
    """
    강의 자료를 로드하여 문자열 또는 File 객체로 반환 (비동기)
    
    Args:
        file_path_or_content: 파일 경로 또는 텍스트 내용
        client: Gemini Client 인스턴스
    
    Returns:
        - PDF 파일: types.File 객체
        - 텍스트 파일/내용: str
    """
    path = pathlib.Path(file_path_or_content)
    
    # 파일이 실제로 존재하는지 확인
    if path.is_file():
        suffix = path.suffix.lower()
        
        if suffix == '.pdf':
            # PDF는 File 객체로 반환 (비동기)
            return await asyncio.to_thread(client.files.upload, file=path)
        elif suffix in ['.txt', '.md', '.py', '.json']:
            # 텍스트 파일은 문자열로 읽기 (비동기)
            try:
                return await asyncio.to_thread(path.read_text, encoding='utf-8')
            except UnicodeDecodeError:
                return await asyncio.to_thread(path.read_text, encoding='cp949')
        else:
            # 지원하지 않는 파일 형식은 텍스트로 시도
            try:
                return await asyncio.to_thread(path.read_text, encoding='utf-8')
            except Exception:
                raise ValueError(f"지원하지 않는 파일 형식입니다: {suffix}")
    else:
        # 파일이 없으면 텍스트 내용으로 간주
        return file_path_or_content


def get_gemini_client(api_key: str = None) -> genai.Client:
    """
    Gemini Client 인스턴스 생성
    
    Args:
        api_key: API 키 (없으면 환경 변수에서 가져옴)
    
    Returns:
        genai.Client 인스턴스
    """
    key = api_key or GEMINI_API_KEY
    if not key:
        raise ValueError("GEMINI_API_KEY가 설정되지 않았습니다.")
    return genai.Client(api_key=key)
