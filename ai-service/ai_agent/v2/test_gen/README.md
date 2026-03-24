# LectureTestGenerator

시험 문제 생성 시스템 - 리팩토링 완료 버전

## 환경 설정

### 필수 환경변수

`.env` 파일을 `ai-service` 루트 디렉토리에 생성하고 다음 환경변수를 설정하세요:

```env
GEMINI_API_KEY=your_gemini_api_key_here
```

### API 키 발급 방법

1. [Google AI Studio](https://aistudio.google.com/app/apikey)에 접속
2. "Create API Key" 클릭
3. 생성된 API 키를 `.env` 파일에 추가

## 사용 방법

### 기본 사용 예시

```python
from ai_agent.LectureTestGenerator import (
    LectureTestGenerator,
    ProblemRequest,
    ExamType
)

# 생성기 초기화
generator = LectureTestGenerator()

# 문제 생성 요청
request = ProblemRequest(
    exam_type=ExamType.FIVE_CHOICE,
    target_count=10,
    lecture_content="강의 자료 텍스트..."
)

# 문제 생성
result = await generator.generate_test(request)

# 결과 확인
print(f"생성된 문제 수: {len(result.problems.mcq_problems)}")
```

### 채점 및 피드백

```python
from ai_agent.LectureTestGenerator import (
    GradingRequest,
    UserAnswer
)

# 사용자 답안
user_answers = [
    UserAnswer(problem_id=1, user_response="3"),
    UserAnswer(problem_id=2, user_response="1"),
]

# 채점 요청
grading_request = GradingRequest(
    exam_type=ExamType.FIVE_CHOICE,
    problems=result.problems.mcq_problems,  # 생성된 문제
    user_answers=user_answers,
    lecture_content="강의 자료..."
)

# 채점 실행
grading_result = await generator.evaluate_test(grading_request)

print(f"총점: {grading_result.total_score}/{grading_result.max_score}")
print(f"피드백: {grading_result.overall_feedback}")
```

## 지원하는 시험 유형

- `ExamType.FIVE_CHOICE`: 5지선다 문제
- `ExamType.OX_PROBLEM`: OX 문제
- `ExamType.FLASH_CARD`: 플래시카드
- `ExamType.SHORT_ANSWER`: 단답형 문제
- `ExamType.DEBATE`: 토론 주제 생성

## 테스트 실행

```bash
# ai-service 디렉토리에서 실행
python -m ai_agent.LectureTestGenerator.main
```

## 구조

```
LectureTestGenerator/
├── __init__.py          # 패키지 노출
├── main.py              # 통합 오케스트레이터
├── profile.py           # 프로필 생성 로직
├── prompts.py           # 프롬프트 중앙화
├── schemas.py           # Pydantic 모델
├── prompt_utils.py      # 스키마 동적 주입
├── utils.py             # 공통 유틸리티
└── generators/          # 각 시험 유형별 Generator
    ├── base.py
    ├── five_choice.py
    ├── ox_problem.py
    ├── flash_card.py
    ├── short_answer.py
    └── debate.py
```
