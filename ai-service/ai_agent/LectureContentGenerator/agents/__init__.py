import os
from dotenv import load_dotenv
from google import genai

# 1. 상위 폴더의 .env 파일 로드
load_dotenv()

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY")

if not GEMINI_API_KEY:
    base_path = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    env_path = os.path.join(base_path, '.env')
    load_dotenv(env_path)
    GEMINI_API_KEY = os.getenv("GEMINI_API_KEY")

if not GEMINI_API_KEY:
    print("⚠️ Warning: GEMINI_API_KEY not found in environment variables.")

# 2. google-genai Client (api_key는 호출 시 전달하거나 여기서 한 번만 생성)
gemini_client = genai.Client(api_key=GEMINI_API_KEY or "") if GEMINI_API_KEY else None

# 3. 모델명 상수
MODEL_FAST = "gemini-2.5-flash"
MODEL_SMART = "gemini-2.0-flash-exp"

GOOGLE_API_KEY = GEMINI_API_KEY
DEFAULT_MODEL = MODEL_SMART
