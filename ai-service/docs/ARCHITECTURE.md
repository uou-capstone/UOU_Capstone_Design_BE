# MergeEduAgent — AI Service 아키텍처 문서

> **버전**: v2.4 (스트리밍 heartbeat 추가, done.data 계약 확정, 채점 버그 수정)  
> **최초 작성**: 2026-03-12 / **최종 수정**: 2026-03-12  
> **설계 기준**: `통합_교육_에이전트.pdf` v1.0 + Spring Boot 개발자 피드백 반영  
> **상세 API 계약**: [`docs/BRIDGE_API.md`](docs/BRIDGE_API.md) / [`docs/TEST_GEN_API.md`](docs/TEST_GEN_API.md)

---

## 1. 시스템 개요

MergeEduAgent는 학습자에게 강의 설명, 질의응답, 퀴즈 생성/채점, 복습 루프를 제공하는 **멀티 에이전트 오케스트레이션 시스템**입니다.

```
Spring Boot / Web ──POST /api/session/{id}/event/stream──► FastAPI OrchestrationEngine
  (세션 기반 학습 흐름)                                    ┌────────┼────────┐
                                                      StateReducer Orchestrator ToolDispatcher
                                                                              │
                                                                   Explainer · QA · Quiz · Grader
                                                                              │
Spring Boot ──────────POST /bridge/quiz  (단건 위임)───────────────► QuizAgents
Spring Boot ──────────POST /bridge/grade (단건 위임)───────────────► GraderAgent
                                                                              │
                                                                   GeminiBridgeClient → Gemini API
```

> **핵심 원칙**: Spring Boot는 오케스트레이션을 담당하지 않습니다.  
> - **세션 흐름**: `/api/session/{id}/event/stream` 단일 진입점으로 이벤트 전달 → FastAPI가 에이전트 호출 순서 결정  
> - **단건 위임**: `/bridge/quiz`, `/bridge/grade`는 "퀴즈 만들어줘", "채점해줘" 같은 원자 작업 전용  
> - `/bridge/*`를 순서대로 여러 번 호출해 오케스트레이션을 구현하는 것은 **금지** 패턴입니다.

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
        ├── session.py             ← 세션 API — 단일 진입점 (오케스트레이션 위임)
        ├── bridge.py              ← Bridge API — 단건 태스크 위임 (/bridge/quiz, /bridge/grade)
        ├── note_gen.py            ← 강의 노트 생성 API
        ├── test_gen.py            ← 시험 생성 API
        ├── pdf.py                 ← PDF 분석 API
        └── upload.py              ← 파일 업로드 API

docs/                              ← Spring Boot 연동 계약 문서
    ├── BRIDGE_API.md              ← 세션/Bridge API 전체 스펙 (Spring Boot 공유용)
    └── TEST_GEN_API.md            ← 시험 생성 스키마 상세 (프론트엔드 공유용)
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
| `NdjsonEvent` | 스트리밍 이벤트 (agent_delta / done / error) — agent/tool/channel/final 필드 포함 |

#### 주요 제약

- **`SessionState.messages`**: 슬라이딩 윈도우 `_MESSAGE_WINDOW = 100` 적용. 최근 100개 메시지만 유지하여 Redis 비대화 방지.
- **`PageState`**: 퀴즈 시도 횟수는 `PageState`가 아닌 `LearnerModel.quiz_attempt_counts` (페이지 키 기준)에서 중앙 관리.

### 3.2 GeminiBridgeClient (`ai_agent/bridge/GeminiBridgeClient.py`)

모든 Gemini API 호출의 **단일 창구**. 세 가지 모드 지원:

| 메서드 | 설명 |
|---|---|
| `generate(contents)` | 비스트리밍 텍스트 생성 (`asyncio.wait_for` 타임아웃 적용) |
| `generate_structured(contents, schema)` | JSON 구조화 출력 (타임아웃 적용) |
| `stream(contents, agent, tool)` | agent_delta / done NDJSON 스트림 (agent/tool/channel 필드 포함) |
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

### 4.1 세션 API (`/api/session`) — 단일 진입점 ★

Spring Boot는 학습 세션의 모든 AI 흐름을 이 단일 진입점으로만 전달합니다.  
어떤 에이전트를 호출할지는 FastAPI OrchestrationEngine이 결정합니다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | `/api/session/by-lecture/{lectureId}` | 세션 조회/생성 |
| `POST` | `/api/session/{sessionId}/event` | 단건 이벤트 (비스트리밍) |
| `POST` | `/api/session/{sessionId}/event/stream` | NDJSON 스트리밍 이벤트 **← 주 진입점** |
| `GET` | `/api/session/{sessionId}/state` | 세션 상태 전체 조회 |
| `DELETE` | `/api/session/{sessionId}` | 세션 삭제 |

#### 이벤트 요청 형식

```json
// 세션 진입 시 (신규 세션이면 lecture_id 필수)
POST /api/session/123/event/stream
{
  "type": "SESSION_ENTERED",
  "lecture_id": 456,
  "payload": {}
}

// 이후 이벤트 (lecture_id 생략 가능 — 세션에 이미 저장됨)
POST /api/session/123/event/stream
{
  "type": "USER_MESSAGE",
  "payload": { "question": "소프트웨어 프로세스의 4가지 활동은?" }
}
```

> `lecture_id`는 **신규 세션 생성 시에만 필수**입니다. 기존 세션 이벤트에서는 생략하면 서버가 세션에서 자동으로 조회합니다.

#### 스트리밍 응답 형식 (NDJSON — agent_delta 규격)

```json
{"type": "agent_delta", "agent": "explainer", "tool": "EXPLAIN_PAGE", "channel": "thought", "delta": "강의 자료 분석 중..."}
{"type": "agent_delta", "agent": "explainer", "tool": "EXPLAIN_PAGE", "channel": "main",    "delta": "소프트웨어 프로세스란..."}
{"type": "done",        "agent": "explainer", "tool": "EXPLAIN_PAGE", "final": true, "data": {"ui": {"widget": "QUIZ_DECISION"}}}
```

### 4.2 Bridge API (`/bridge`) — 단건 태스크 위임 전용

Spring Boot가 학습 세션 흐름과 무관하게 단건 AI 작업만 필요할 때 사용합니다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| `POST` | `/bridge/quiz` | 강의 내용 → 퀴즈 생성 |
| `POST` | `/bridge/grade` | 답안 → 채점 결과 |

> **⚠ 금지 패턴**: `/bridge/*`를 순서대로 여러 번 호출해 흐름을 구성하는 것은 Spring Boot가 오케스트레이션을 수행하는 것과 동일하므로 허용하지 않습니다.

#### 요청/응답 예시

```json
// 퀴즈 생성
POST /bridge/quiz
{
  "exam_type": "Five_Choice",
  "lecture_content": "...",
  "target_count": 5,
  "user_profile": null
}

// 응답 (NDJSON)
{"type": "agent_delta", "agent": "quiz", "tool": "GENERATE_QUIZ", "channel": "thought", "delta": "퀴즈 생성 중..."}
{"type": "done", "agent": "quiz", "tool": "GENERATE_QUIZ", "final": true, "data": {"quiz": [...], "quiz_type": "Five_Choice"}}
```

```json
// 채점
POST /bridge/grade
{
  "exam_type": "Five_Choice",
  "problems": [ /* done.data.quiz 배열 그대로 */ ],
  "user_answers": [
    { "problem_id": 1, "user_response": "2" }
  ],
  "lecture_content": "..."
}
```

> 상세 필드 명세는 [`docs/BRIDGE_API.md`](docs/BRIDGE_API.md) §3 참고

### 4.3 기타 API

| 경로 | 설명 |
|---|---|
| `/api/lecture-gen/phase3-5/auto` | 강의 노트 자동 생성 (Phase3~5) |
| `/api/test-gen/profile` | 시험 프로필 분석 |
| `/api/test-gen/generate` | 시험 문항 생성 |
| `/api/pdf/analyze` | PDF 챕터 분석 |
| `/api/files/upload` | 파일 업로드 |
| `/health` | 헬스 체크 (Redis 상태 포함) |

---

## 5. 스트리밍 이벤트 타입 (agent_delta 규격)

### 이벤트 필드

| 필드 | 타입 | 설명 |
|---|---|---|
| `type` | string | `"agent_delta"` \| `"done"` \| `"error"` \| `"heartbeat"` |
| `agent` | string | `"explainer"` \| `"qa"` \| `"quiz"` \| `"grader"` \| `"system"` (`heartbeat` 제외) |
| `tool` | string? | 호출 툴 (e.g. `"EXPLAIN_PAGE"`, `"ANSWER_QUESTION"`, `"GENERATE_QUIZ"`, `"GRADE"`) |
| `channel` | string? | `agent_delta` 전용 — `"thought"` (내부 추론) \| `"main"` (실제 답변) |
| `delta` | string? | `agent_delta` 텍스트 청크 (짧은 단위로 자주 전송) |
| `final` | bool? | `done` 이벤트에서 `true` |
| `data` | object? | `done` 이벤트 부가 데이터 |
| `message` | string? | `error` 이벤트 오류 메시지 |

> **heartbeat**: LLM 응답 대기 중 10초마다 자동 전송되는 연결 유지 이벤트.  
> `{"type": "heartbeat"}` 한 줄만 포함. 클라이언트가 무시해도 됩니다.  
> **error 종료 보장**: 스트림이 오류로 끝날 때 반드시 `type: "error"` 이벤트가 마지막에 전송됩니다.

### 이벤트 타입별 예시

```jsonl
{"type": "agent_delta", "agent": "explainer", "tool": "EXPLAIN_PAGE", "channel": "thought", "delta": "강의 자료 분석 중..."}
{"type": "agent_delta", "agent": "explainer", "tool": "EXPLAIN_PAGE", "channel": "main",    "delta": "소프트웨어 프로세스란..."}
{"type": "heartbeat"}
{"type": "done",        "agent": "explainer", "tool": "EXPLAIN_PAGE", "final": true, "data": {"ui": {"widget": "QUIZ_DECISION"}}}
{"type": "error",       "agent": "system",    "message": "서버 오류가 발생했습니다."}
```

#### `done.data` 필드 상세

> **계약**: 아래 필드명은 Breaking Change 없이 변경되지 않습니다. 상세 스펙은 [`docs/BRIDGE_API.md §5`](docs/BRIDGE_API.md) 참고.

| 상황 | data 내용 |
|---|---|
| 설명 완료 후 퀴즈 제안 | `{"ui": {"widget": "QUIZ_DECISION"}}` |
| 퀴즈 생성 완료 | `{"quiz": [...], "quiz_type": "Five_Choice"}` |
| 채점 완료 (통과) | `{"grading": {"results": [...], "total_score": 0.8, "overall_feedback": "..."}, "passed": true}` |
| 채점 완료 (미통과) | `{"grading": {...}, "passed": false}` |
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

### 7.1 전체 변경 이력

| 항목 | 변경 전 | 변경 후 |
|---|---|---|
| 강의 이벤트 API | `POST /api/delegator/dispatch` | `POST /api/session/{id}/event/stream` |
| 세션 생성/조회 | 없음 | `GET /api/session/by-lecture/{lectureId}` |
| 단건 퀴즈/채점 | 없음 | `POST /bridge/quiz`, `POST /bridge/grade` |
| 스트리밍 포맷 | SSE (`data: ...\n\n`) | NDJSON (`{...}\n`) |
| 이벤트 타입 | `thought_delta` / `answer_delta` | `agent_delta` (channel 필드로 구분) |
| 이벤트 모델 | `stage` 문자열 | `AppEventType` Enum |
| `EventRequest.lecture_id` | 매 요청 필수 | 신규 세션 생성 시에만 필수, 이후 생략 가능 |
| `/bridge/quiz` 필드명 | `quiz_type`, `count`, `profile` | `exam_type`, `target_count`, `user_profile` |
| `/bridge/grade` 답안 형식 | `List[Any]` (인덱스 순) | `List[UserAnswer]` (problem_id 기준) |
| 스트리밍 heartbeat | 없음 | `{"type":"heartbeat"}` 10초마다 자동 전송 |
| 스트림 오류 종료 | 연결 끊김 | 반드시 `type:error` 이벤트 후 종료 보장 |
| `done.data` 필드명 | 미확정 | `BRIDGE_API.md §5` 계약으로 고정 |
| 채점 결과 필드 | `question_index`, `score`, `passed`, `feedback` | 동일 + `user_answer`, `correct_answer` 추가 |

### 7.2 Spring Boot 연동 패턴

```
✅ 허용: 학습 세션 흐름
  1) GET /api/session/by-lecture/{lectureId}?pdf_path=... → session_id 수령
  2) POST /api/session/{id}/event/stream  {"type": "SESSION_ENTERED", "lecture_id": 456}
  3) POST /api/session/{id}/event/stream  {"type": "USER_MESSAGE", "payload": {...}}
     ← 이후 요청은 lecture_id 생략 가능

✅ 허용: 단건 태스크 위임
  POST /bridge/quiz  {"exam_type": "OX_Problem", "lecture_content": "...", "target_count": 5}
  POST /bridge/grade {"exam_type": "OX_Problem", "problems": [...],
                      "user_answers": [{"problem_id": 1, "user_response": "O"}]}

❌ 금지: Spring Boot가 bridge를 순서대로 호출해 흐름 구성
  /bridge/quiz → 결과 수령 → /bridge/grade  (오케스트레이션 금지)
```

> 전체 API 계약 및 `done.data` 스펙 상세: [`docs/BRIDGE_API.md`](docs/BRIDGE_API.md)

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
