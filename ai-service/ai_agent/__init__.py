import os
from dotenv import load_dotenv

# --- 환경 변수 로드 ---
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
env_path = os.path.join(BASE_DIR, ".env")

if os.path.exists(env_path):
    load_dotenv(env_path)
    print(f"[ai_agent] .env loaded from: {env_path}")
else:
    print("[ai_agent] .env not found, using system environment variables.")

# --- 하위 모듈 import ---
from ai_agent.Lecture_Agent.component import (
    MainLectureAgent,
    MainQandAAgent,
    PdfAnalysis,
)

__all__ = ["MainLectureAgent", "MainQandAAgent", "PdfAnalysis"]

print("[ai_agent] initialized successfully.")