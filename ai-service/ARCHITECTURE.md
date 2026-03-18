# MergeEduAgent — AI Service 아키텍처 문서

> **버전**: v2.1 (코드 리뷰 및 최적화 반영)  
> **최초 작성**: 2026-03-12 / **최종 수정**: 2026-03-17  
> **설계 기준**: `통합_교육_에이전트.pdf` v1.0

---

## 1. 시스템 개요

MergeEduAgent는 학습자에게 강의 설명, 질의응답, 퀴즈 생성/채점, 복습 루프를 제공하는 **멀티 에이전트 오케스트레이션 시스템**입니다.

```
Web(React) ──POST /api/session/:id/event/stream──► FastAPI (ai-service)
                                                       │
                                              OrchestrationEngine
                                             ┌─────────┼─────────┐
                                        StateReducer  Orchestrator  ToolDispatcher
                                                          │              │
                                                    (규칙 기반 계획)   (에이전트 실행)
                                                                    ┌───┼───┐───┐
                                                              Explainer  QA  Quiz  Grader
                                                                    │
                                                          GeminiBridgeClient
                                                                    │
                                                          Google Gemini API
```

---

## 2. 디렉토리 구조

```
ai-service/
├── ai_agent/
│   ├── types/
│   │   └── domain.py              ← 도메인 타입 전체 (AppEvent, SessionState 등)
│   ├── bridge/
│   │   └── GeminiBridgeClient.py  ← Gemini API 중앙 브리지 (NDJSON 포맷 통일)
│   ├── agents/
│   │   ├── ExplainerAgent.py      ← 강의 설명 생성
│   │   ├── QaAgent.py             ← 질문 답변
│   │   ├── QuizAgents.py          ← 퀴즈 생성 (LectureTestGenerator 위임)
│   │   └── GraderAgent.py         ← 퀴즈 채점 (자동/LLM)
│   ├── engine/
│   │   ├── StateReducer.py        ← 이벤트 즉시 선반영
│   │   ├── Orchestrator.py        ← 규칙 기반 계획 수립
│   │   ├── ToolDispatcher.py      ← 에이전트 실행 + soft-failure
│   │   └── OrchestrationEngine.py ← 파이프라인 조립 (진입점)
│   ├── Lecture_Agent/
│   │   └── component/
│   │       ├── MainQandAAgent.py  ← GOOD/BAD 답변 검증 (독립 사용 가능)
│   │       └── PdfAnalysis.py     ← PDF/MD 챕터 분할 유틸리티
│   ├── LectureContentGenerator/   ← 강의 노트 생성 (Phase3~5, 별도 파이프라인)
│   └── LectureTestGenerator/      ← 시험 문항 생성기 (QuizAgents가 위임)
│
└── app/
    ├── main.py                    ← FastAPI 앱 진입점
    ├── core/
    │   ├── redis_client.py        ← Redis 연결 관리
    │   └── session_store.py       ← 세션 영속화 (Redis, TTL 24h)
    └── routers/
        ├── session.py             ← 통합 세션 API (핵심 진입점)
        ├── note_gen.py            ← 강의 노트 생성 API
        ├── test_gen.py            ← 시험 생성 API
        ├── pdf.py                 ← PDF 분석 API
        └── upload.py              ← 파일 업로드 API
```

---

## 3. 핵심 컴포넌트 상세

### 3.1 도메인 타입 (`ai_agent/types/domain.py`)

| 타입 | 설명 |
|---|---|
| `AppEventType` | 10개 이벤트 Enum (SESSION_ENTERED, USER_MESSAGE 등) |
| `AppEvent` | 이벤트 컨테이너 (type + payload) |
| `PageStatus` | 페이지 상태 머신 (NEW → EXPLAINING → ... → DONE) |
| `PageState` | 단일 페이지 상태 (설명, is_key_page 등) |
| `LearnerModel` | 학습자 모델 (점수 이력, 약점 개념, 페이지별 퀴즈 시도 횟수) |
| `SessionState` | 세션 전체 상태 (pages + learner + quiz_history + messages) |
| `OrchestratorPlan` | 실행 계획 (OrchestratorAction 목록) |
| `NdjsonEvent` | 스트리밍 이벤트 (thought_delta / answer_delta / done / error) |

#### 주요 제약

- **`SessionState.messages`**: 슬라이딩 윈도우 `_MESSAGE_WINDOW = 100` 적용. 최근 100개 메시지만 유지하여 Redis 비대화 방지.
- **`PageState`**: 퀴즈 시도 횟수는 `PageState`가 아닌 `LearnerModel.quiz_attempt_counts` (페이지 키 기준)에서 중앙 관리.

### 3.2 GeminiBridgeClient (`ai_agent/bridge/GeminiBridgeClient.py`)

모든 Gemini API 호출의 **단일 창구**. 세 가지 모드 지원:

| 메서드 | 설명 |
|---|---|
| `generate(contents)` | 비스트리밍 텍스트 생성 (`asyncio.wait_for` 타임아웃 적용) |
| `generate_structured(contents, schema)` | JSON 구조화 출력 (타임아웃 적용) |
| `stream(contents)` | thought_delta / answer_delta / done NDJSON 스트림 |
| `load_pdf_part(pdf_path)` | PDF → Gemini Part 변환 (`@lru_cache(maxsize=32)`) |
| `load_text(text_path)` | MD/TXT 텍스트 읽기 (다중 인코딩 fallback) |

#### 스트리밍 구현 방식

Gemini SDK의 `generate_content_stream()`은 동기 이터레이터를 반환한다. 이를 그대로 async 코루틴에서 순회하면 이벤트 루프가 블로킹된다.

**해결책**: `asyncio.Queue` + `run_in_executor` 패턴을 사용하여 sync 이터레이터를 별도 스레드에서 소비하고, 결과를 큐를 통해 async 스트림으로 전달한다.

```
[Thread Pool]                     [Event Loop]
 generate_content_stream()
   for chunk in iter:
     loop.call_soon_threadsafe(     →   queue.put_nowait(event)
       queue.put_nowait, event)              ↓
                                       async for event in stream():
                                         yield event
```

#### 타임아웃 설정

- 환경변수 `GEMINI_STREAM_TIMEOUT` (기본값: `300`초)
- 비스트리밍: `asyncio.wait_for(timeout=GEMINI_STREAM_TIMEOUT)`
- 스트리밍: 청크 수신 대기에 동일 타임아웃 적용

#### PDF 캐싱

`load_pdf_part`에 `@lru_cache(maxsize=32)` 적용. 동일 PDF 경로는 재읽기 없이 캐시에서 반환한다. 서버 재시작 시 캐시 초기화.

### 3.3 서브 에이전트 (`ai_agent/agents/`)

모든 에이전트는 동일한 `runStream / run` 인터페이스를 구현합니다.

| 에이전트 | 역할 | 호출 도구 |
|---|---|---|
| `ExplainerAgent` | 챕터 설명 스트리밍 생성 | `EXPLAIN_PAGE` |
| `QaAgent` | 사용자 질문 답변 | `ANSWER_QUESTION` |
| `QuizAgents` | 퀴즈 생성 (LectureTestGenerator 위임) | `GENERATE_QUIZ_*` |
| `GraderAgent` | MCQ/OX 자동 채점, 단답/서술 LLM 채점 | `AUTO_GRADE_MCQ_OX`, `GRADE_SHORT_OR_ESSAY` |

### 3.4 오케스트레이션 엔진 (`ai_agent/engine/`)

```
이벤트 수신
    │
    ▼
StateReducer.reduce()   ← 이벤트 즉시 선반영 (페이지 이동, 유저 메시지)
    │
    ▼
Orchestrator.run()      ← 규칙 기반 계획 수립 (OrchestratorPlan 반환)
    │
    ▼
ToolDispatcher.dispatch() ← 에이전트 호출 + 상태 업데이트 (soft-failure)
    │
    ▼
SessionStore.set()      ← Redis 세션 저장
    │
    ▼
NdjsonEvent 스트림 반환
```

#### Orchestrator 이벤트-분기 규칙

| 이벤트 | 생성 액션 |
|---|---|
| `SESSION_ENTERED` | SEND_MESSAGE(환영) + START_EXPLANATION_DECISION 위젯 |
| `START_EXPLANATION_DECISION(accept=true)` | CALL_TOOL(EXPLAIN_PAGE) + (조건부) QUIZ_DECISION 위젯 |
| `PAGE_CHANGED` | CALL_TOOL(EXPLAIN_PAGE) + QUIZ_DECISION 또는 NEXT_PAGE_DECISION |
| `USER_MESSAGE` | 퀴즈 의도 → QUIZ_TYPE_PICKER / next 명령 → PAGE_CHANGED 흐름 / 일반 질문 → ANSWER_QUESTION |
| `QUIZ_TYPE_SELECTED` | CALL_TOOL(GENERATE_QUIZ_{type}) |
| `QUIZ_SUBMITTED(MCQ/OX)` | CALL_TOOL(AUTO_GRADE_MCQ_OX) |
| `QUIZ_SUBMITTED(SHORT/ESSAY)` | CALL_TOOL(GRADE_SHORT_OR_ESSAY) |
| `REVIEW_DECISION(accept=true)` | CALL_TOOL(EXPLAIN_PAGE, detail=DETAILED) + RETEST_DECISION |
| `RETEST_DECISION(accept=true)` | SET_UI(QUIZ_TYPE_PICKER) |
| `SAVE_AND_EXIT` | SEND_MESSAGE(저장 완료) |

#### Orchestrator 정책 함수

| 함수 | 조건 |
|---|---|
| `_should_use_detailed_explanation()` | 최근 점수 < 0.6 또는 약점 개념 또는 LLM 힌트 |
| `_should_offer_quiz()` | 핵심 페이지 또는 최근 점수 < 0.7 또는 LLM 힌트 (시도 횟수 2회 미만) |
| `_recommend_quiz_type()` | BEGINNER→OX / ADVANCED→Short_Answer / 기본→Five_Choice |

> `_recommend_quiz_type()`은 `QUIZ_TYPE_SELECTED` 이벤트에서 프론트가 `quizType`을 명시하지 않았을 때 fallback으로 사용된다.

### 3.5 SessionStore (`app/core/session_store.py`)

- Redis 기반 세션 영속화
- 키 형식: `edu_session:{session_id}`
- TTL: 24시간 자동 만료 (`SESSION_TTL = 86400`)
- `get_or_create(session_id, lecture_id)`: 조회 없으면 신규 생성
- Redis 연결 실패 시 `None` 반환 + 경고 로그 (앱 크래시 없음)

### 3.6 ToolDispatcher 주요 동작 규칙

| 도구 | 주요 부수효과 |
|---|---|
| `EXPLAIN_PAGE` | `page_state.explanation` 최대 2,000자 저장 (Redis 비대화 방지), `PageStatus.EXPLAINED`로 전이 |
| `ANSWER_QUESTION` | QaAgent 스트리밍 실행, 상태 변경 없음 |
| `GENERATE_QUIZ_*` | `QuizRecord` 생성 후 `state.quiz_history`에 추가, `PageStatus.QUIZ_IN_PROGRESS` 전이 |
| `AUTO_GRADE_MCQ_OX` | 서버 내부 정답 비교 채점, 기준 미달 시 `REVIEW_DECISION` 위젯 자동 추가 |
| `GRADE_SHORT_OR_ESSAY` | LLM 채점, 기준 미달 시 `REVIEW_DECISION` 위젯 자동 추가 |
| 도구 실패 시 | `[SYSTEM] AI 도구 실행 실패` 메시지로 degrade (흐름 유지) |

---

## 4. API 엔드포인트

### 4.1 세션 API (`/api/session`) — 핵심

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | `/api/session/by-lecture/{lectureId}` | 세션 조회/생성 |
| `POST` | `/api/session/{sessionId}/event` | 단건 이벤트 (비스트리밍) |
| `POST` | `/api/session/{sessionId}/event/stream` | NDJSON 스트리밍 이벤트 |
| `GET` | `/api/session/{sessionId}/state` | 세션 상태 전체 조회 |
| `DELETE` | `/api/session/{sessionId}` | 세션 삭제 |

#### 이벤트 요청 형식

```json
POST /api/session/123/event/stream
{
  "type": "SESSION_ENTERED",
  "lecture_id": 456,
  "payload": {}
}
```

#### 스트리밍 응답 형식 (NDJSON)

```
{"type": "thought_delta", "delta": "강의 자료 분석 중..."}
{"type": "answer_delta", "delta": "소프트웨어 프로세스란..."}
{"type": "done", "data": {"ui": {"widget": "QUIZ_DECISION"}}}
```

### 4.2 기타 API

| 경로 | 설명 |
|---|---|
| `/api/lecture-gen/phase3-5/auto` | 강의 노트 자동 생성 (Phase3~5) |
| `/api/test-gen/profile` | 시험 프로필 분석 |
| `/api/test-gen/generate` | 시험 문항 생성 |
| `/api/pdf/analyze` | PDF 챕터 분석 |
| `/api/files/upload` | 파일 업로드 |
| `/health` | 헬스 체크 (Redis 상태 포함) |

---

## 5. 스트리밍 이벤트 타입

| type | delta | data | message | 설명 |
|---|---|---|---|---|
| `thought_delta` | 추론 텍스트 | - | - | AI 내부 사고 과정 |
| `answer_delta` | 답변 텍스트 | - | - | 실제 답변 (점진적 출력) |
| `done` | - | `{ui?, quiz?, grading?, ...}` | - | 처리 완료 |
| `error` | - | - | 오류 메시지 | 오류 발생 |

#### `done.data` 필드 상세

| 상황 | data 내용 |
|---|---|
| 설명 완료 후 퀴즈 제안 | `{"ui": {"widget": "QUIZ_DECISION"}}` |
| 퀴즈 생성 완료 | `{"quiz": {...}, "quiz_type": "Five_Choice"}` |
| 채점 완료 (통과) | `{"grading": {...}, "passed": true}` |
| 채점 완료 (미통과) | `{"grading": {...}, "passed": false, "ui": {"widget": "REVIEW_DECISION"}}` |
| 다음 페이지 결정 | `{"ui": {"widget": "NEXT_PAGE_DECISION"}}` |
| 퀴즈 유형 선택 요청 | `{"ui": {"modal": "QUIZ_TYPE_PICKER"}}` |

---

## 6. 삭제된 레거시 파일 목록

리팩터링 과정에서 아래 파일들이 제거되었습니다.

| 삭제된 파일 | 대체 항목 |
|---|---|
| `app/routers/delegator.py` (1155줄) | `app/routers/session.py` + `OrchestrationEngine` |
| `app/routers/qa.py` | `QaAgent` + 세션 라우터 |
| `app/routers/lecture.py` | `ExplainerAgent` + 세션 라우터 |
| `app/services/lecture_gen.py` | `ExplainerAgent` 내부 로직 |
| `app/services/qa_service.py` | `QaAgent` 내부 로직 |
| `ai_agent/Lecture_Agent/integration.py` | `OrchestrationEngine` |
| `ai_agent/Lecture_Agent/component/MainLectureAgent.py` | `ExplainerAgent` |

---

## 7. Spring Boot 연동 변경 사항

| 항목 | 변경 전 | 변경 후 |
|---|---|---|
| 강의 이벤트 API | `POST /api/delegator/dispatch` | `POST /api/session/:id/event/stream` |
| 세션 생성/조회 | 없음 | `GET /api/session/by-lecture/:lectureId` |
| 스트리밍 포맷 | SSE (`data: ...\n\n`) | NDJSON (`{...}\n`) |
| 이벤트 모델 | `stage` 문자열 | `AppEventType` Enum |

---

## 8. 확장 포인트

설계서 §13 기준:

| 확장 항목 | 방법 |
|---|---|
| 신규 서브 에이전트 추가 | `ToolName` Enum 추가 → `Orchestrator` 분기 추가 → `ToolDispatcher._execute_tool` 케이스 추가 |
| 정책 고도화 | `Orchestrator._should*` 함수를 별도 정책 모듈로 분리 |
| LLM 힌트 연동 | `OrchestrationEngine`에서 Gemini 호출 후 `Orchestrator.run(llm_hint=...)` 주입 |
| 저장소 교체 | `SessionStore` 인터페이스를 유지하며 Redis → DB로 교체 |
| 상태 머신 강화 | `PageStatus` 전이를 명시적 검증기(guard)로 강제 |
| PDF 캐시 크기 조정 | `GeminiBridgeClient.load_pdf_part` `lru_cache(maxsize=N)` 값 변경 |
| 타임아웃 조정 | `GEMINI_STREAM_TIMEOUT` 환경변수 설정 (기본 300초) |

---

## 9. 환경변수 목록

| 변수명 | 기본값 | 설명 |
|---|---|---|
| `GEMINI_API_KEY` | (필수) | Google Gemini API 키 |
| `REDIS_HOST` | `localhost` | Redis 호스트 |
| `REDIS_PORT` | `6379` | Redis 포트 |
| `GEMINI_STREAM_TIMEOUT` | `300` | Gemini API 타임아웃 (초) |
