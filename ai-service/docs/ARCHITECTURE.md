# MergeEduAgent — AI Service 아키텍처 문서

> **버전**: v2.8 (`QuizAgents` profile 기본값 주입 · 오류 이벤트 payload 표준화 · Explainer 출력 포맷 강화)  
> **최초 작성**: 2026-03-12 / **최종 수정**: 2026-04-08  
> **설계 기준**: `통합_교육_에이전트.pdf` v1.0 + Spring Boot 개발자 피드백 반영  
> **상세 API 계약**: [`docs/BRIDGE_API.md`](docs/BRIDGE_API.md) / [`docs/TEST_GEN_API.md`](docs/TEST_GEN_API.md)

### 변경 이력

| 버전 | 날짜 | 주요 변경 |
|---|---|---|
| v2.7 | 2026-03-24 | `ai_agent/` v2/v3 트랙 분리 · 하이브리드 PDF 로딩(File API + Redis/메모리 캐시) · `path_validator` 경로 검증 · 업로드 UUID·확장자 화이트리스트 · Bridge grade 길이 검증(400) · 세션 `lecture_id` IDOR 방지 · 스트리밍 `QueueFull`/`_safe_put` 보강 · File API 캐시 LRU 상한 · 공유 Redis 풀 · Debate 세션 명시 안내 · `MainQandAAgent` 이벤트 루프 수정 |
| v2.8 | 2026-04-08 | `QuizAgents`가 `user_profile` 미전달/부분 전달을 허용하도록 기본값 주입+deep-merge 후 검증 · `error.data` 표준 오류 payload(code/message/details) 추가 · `ExplainerAgent` 마크다운 구조화/질문 태그 강제 |
| v2.6 | 2026-03-12 | v2/v3 URL 버전 접두사 적용 · ExplainerAgent 페이지 기반 전환 · GraderAgent LLM 채점 강화 |
| v2.5 | 2026-03-12 | 통합 에이전트 리팩터링 · OrchestrationEngine 구현 · NDJSON agent_delta 포맷 통일 |

---

## 1. 시스템 개요

MergeEduAgent는 학습자에게 강의 설명, 질의응답, 퀴즈 생성/채점, 복습 루프를 제공하는 **멀티 에이전트 오케스트레이션 시스템**입니다.

```
[v2 Classic Track]
Spring Boot ──POST /api/v2/lectures/generate──► ExplainerAgent (단건, 세션 없음)
Spring Boot ──POST /api/v2/qa/evaluate       ──► QA Evaluator  (단건, 세션 없음)
Spring Boot ──POST /api/v2/test-gen/generate ──► LectureTestGenerator

[v3 Integrated Track]
Spring Boot ──POST /api/v3/session/{id}/event/stream──► FastAPI OrchestrationEngine
  (세션 기반 학습 흐름)                                  ┌────────┼────────┐
                                                    StateReducer Orchestrator ToolDispatcher
                                                                            │
                                                                 Explainer · QA · Quiz · Grader

Spring Boot ──POST /api/v3/bridge/quiz  (단건 위임)────► QuizAgents
Spring Boot ──POST /api/v3/bridge/grade (단건 위임)────► GraderAgent
                                                                            │
                                                                 GeminiBridgeClient → Gemini API
```

> **핵심 원칙**: Spring Boot는 오케스트레이션을 담당하지 않습니다.  
> - **v2**: 개별 버튼 클릭 시 각 엔드포인트 독립 호출 (세션 없음)  
> - **v3 세션 흐름**: `/api/v3/session/{id}/event/stream` 단일 진입점 → FastAPI가 에이전트 순서 결정  
> - **v3 단건 위임**: `/api/v3/bridge/quiz`, `/api/v3/bridge/grade`는 원자 작업 전용  
> - `/api/v3/bridge/*`를 순서대로 여러 번 호출해 오케스트레이션을 구현하는 것은 **금지** 패턴입니다.

---

## 2. 디렉토리 구조

> **v2.7 변경**: `ai_agent/` 내부 패키지가 트랙별로 분리되었습니다.  
> 이전 `agents/`, `engine/`, `LectureContentGenerator/`, `LectureTestGenerator/`, `Lecture_Agent/` 는 모두 제거되었습니다.

```
ai-service/
├── ai_agent/
│   ├── types/
│   │   └── domain.py                    ← 도메인 타입 전체 (AppEvent, SessionState 등)
│   ├── bridge/
│   │   └── GeminiBridgeClient.py        ← Gemini API 중앙 브리지 (NDJSON 포맷 통일)
│   │
│   ├── v3/                              ← [v3 Integrated Track] 통합 오케스트레이션
│   │   ├── agents/
│   │   │   ├── ExplainerAgent.py        ← 페이지 단위 강의 설명 스트리밍
│   │   │   ├── QaAgent.py               ← 질문 답변
│   │   │   ├── QuizAgents.py            ← 퀴즈 생성 (v2/test_gen 위임)
│   │   │   └── GraderAgent.py           ← 퀴즈 채점 (자동/LLM, PDF 입력 지원)
│   │   └── engine/
│   │       ├── StateReducer.py          ← 이벤트 즉시 선반영
│   │       ├── Orchestrator.py          ← 규칙 기반 계획 수립
│   │       ├── ToolDispatcher.py        ← 에이전트 실행 + soft-failure
│   │       └── OrchestrationEngine.py   ← 파이프라인 조립 (v3 진입점)
│   │
│   └── v2/                              ← [v2 Classic Track] 개별 에이전트
│       ├── note_gen/                    ← 강의 노트 생성 (Phase1~5, 별도 파이프라인)
│       │   ├── main.py
│       │   ├── schemas.py
│       │   ├── prompts.py
│       │   └── agents/
│       │       ├── phase1_planning.py
│       │       ├── phase2_briefing.py
│       │       ├── phase3_research.py   ← Google Search RAG 사용
│       │       ├── phase4_review.py
│       │       └── phase5_assembly.py
│       ├── test_gen/                    ← 시험 문항 생성기 (QuizAgents가 위임)
│       │   ├── main.py
│       │   ├── schemas.py
│       │   ├── profile.py
│       │   ├── prompts.py
│       │   └── generators/
│       │       ├── five_choice.py
│       │       ├── ox_problem.py
│       │       ├── flash_card.py
│       │       └── short_answer.py
│       └── legacy/                      ← 레거시 컴포넌트 (v2 QA 서비스용)
│           ├── MainQandAAgent.py        ← GOOD/BAD 답변 검증
│           └── PdfAnalysis.py           ← PDF/MD 챕터 분할 유틸리티
│
└── app/
    ├── main.py                          ← FastAPI 앱 진입점
    ├── core/
    │   ├── redis_client.py              ← Redis 연결 관리
    │   ├── session_store.py             ← 세션 영속화 (Redis, TTL 24h)
    │   └── path_validator.py            ← [v2.7] pdf_path 경로 순회 취약점 방어 유틸
    └── routers/
        ├── session.py                   ← [v3] 세션 API — 단일 진입점
        ├── bridge.py                    ← [v3] Bridge API — 단건 태스크 위임
        ├── lecture.py                   ← [v2] 강의 설명 API
        ├── qa.py                        ← [v2] QA 평가 API
        ├── note_gen.py                  ← [v2] 강의 노트 생성 API
        ├── test_gen.py                  ← [v2] 시험 생성 API
        ├── pdf.py                       ← 공용 PDF 분석 API
        └── upload.py                    ← 공용 파일 업로드 API

docs/
    ├── ARCHITECTURE.md                  ← 이 문서
    ├── BRIDGE_API.md                    ← 세션/Bridge API 전체 스펙 (Spring Boot 공유용)
    ├── TEST_GEN_API.md                  ← 시험 생성 스키마 상세
    └── FE_API.md                        ← 프론트엔드 API 통합 가이드
```

### 2.1 import 경로 규칙

| 구 경로 (v2.6 이전) | 현재 경로 (v2.7~) |
|---|---|
| `ai_agent.agents.ExplainerAgent` | `ai_agent.v3.agents.ExplainerAgent` |
| `ai_agent.agents.GraderAgent` | `ai_agent.v3.agents.GraderAgent` |
| `ai_agent.agents.QaAgent` | `ai_agent.v3.agents.QaAgent` |
| `ai_agent.agents.QuizAgents` | `ai_agent.v3.agents.QuizAgents` |
| `ai_agent.engine.*` | `ai_agent.v3.engine.*` |
| `ai_agent.LectureContentGenerator.*` | `ai_agent.v2.note_gen.*` |
| `ai_agent.LectureTestGenerator.*` | `ai_agent.v2.test_gen.*` |
| `ai_agent.Lecture_Agent.component.*` | `ai_agent.v2.legacy.*` |
| `ai_agent.bridge.*` / `ai_agent.types.*` | 변경 없음 (공용) |

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
| `async load_pdf_part(pdf_path)` | PDF → Gemini Part 변환 (**v2.7: 하이브리드 캐시 전략**) |
| `load_text(text_path)` | MD/TXT 텍스트 읽기 (다중 인코딩 fallback) |

#### 스트리밍 구현 방식

Gemini SDK의 `generate_content_stream()`은 동기 이터레이터를 반환한다. 이를 그대로 async 코루틴에서 순회하면 이벤트 루프가 블로킹된다.

**해결책**: `asyncio.Queue` + `run_in_executor` 패턴을 사용하여 sync 이터레이터를 별도 스레드에서 소비하고, 결과를 큐를 통해 async 스트림으로 전달한다.

```
[Thread Pool]                            [Event Loop]
 generate_content_stream()
   for chunk in iter:
     if _cancelled.is_set(): break  ←── _safe_put이 QueueFull 감지 시 set()
     loop.call_soon_threadsafe(
       _safe_put, event)            →    _safe_put():
                                           try: queue.put_nowait(event)
                                           except QueueFull: _cancelled.set()
                                                ↓
                                        async for event in stream():
                                          yield event
```

**v2.7 스트리밍 안정성 보강 (동일 메이저 버전 내 패치)**:

| 항목 | 보강 이전 | v2.7 현재 |
|---|---|---|
| Queue maxsize | 무제한 | `maxsize=200` — 소비자 단절 시 Producer 큐 무한 증가 방지 |
| QueueFull 처리 | Producer 스레드의 `try/except`로 포착 시도 (실제로 잡히지 않음) | `_safe_put()` 래퍼를 Event Loop에서 호출 — `QueueFull` 즉시 포착 후 `_cancelled.set()` |
| Producer 조기 종료 | 큐가 꽉 차도 계속 생산 | `threading.Event(_cancelled)` — 큐 포화 또는 소비자 종료 시 Producer 즉시 중단 |
| `import threading` | `stream()` 함수 내부마다 실행 | 모듈 최상단으로 이동 |

> **영향**: 소비자(클라이언트)가 스트림을 중간에 끊거나 응답을 처리 못 할 때 서버 메모리가 쌓이던 잠재 문제가 해결됩니다.

#### 타임아웃 설정

- 환경변수 `GEMINI_STREAM_TIMEOUT` (기본값: `300`초)
- 비스트리밍: `asyncio.wait_for(timeout=GEMINI_STREAM_TIMEOUT)`
- 스트리밍: 청크 수신 대기에 동일 타임아웃 적용

#### PDF 하이브리드 로딩 (`load_pdf_part`) — v2.7 신규

> **왜 필요한가**: 교재급 대용량 PDF(> 15 MB)를 매 요청마다 인라인으로 전송하면 20 MB 제한 초과 및 반복 전송 오버헤드가 발생한다. File API를 사용하면 Google 서버에 한 번만 업로드하고 이후 요청에서 URI를 재사용할 수 있다.

```
요청 → load_pdf_part(pdf_path)
         │
         ├─ FileNotFoundError (파일 없음)
         │
         ├─ 파일 크기 < PDF_INLINE_MB (기본 15 MB)
         │     └─ _load_bytes_cached(path, mtime_ns)  ← LRU 캐시, 동기, 즉시
         │          (mtime_ns 포함으로 파일 교체 시 자동 갱신)
         │
         └─ 파일 크기 ≥ PDF_INLINE_MB
               └─ asyncio.Lock 획득 (파일별)
                    │
                    ├─ Redis 캐시 조회 (L1) ─── mtime 일치 + TTL 유효 → URI 재사용 ✅
                    ├─ 메모리 캐시 조회 (L2) ── (Redis 장애 시 fallback)
                    │
                    └─ 캐시 미스 / 만료 / 파일 변경
                         ├─ File API 업로드 (asyncio.to_thread, 비블로킹)
                         │    ├─ 성공: Redis(TTL 47h) + 메모리에 URI 저장
                         │    └─ 실패: Part.from_bytes() 폴백 ⚠️ 로그 경고
                         └─ Lock 해제
```

**캐시 키 설계**

| 레이어 | 키 | TTL | 특징 |
|---|---|---|---|
| Redis (L1) | `fa:file_api:{md5(path)[:16]}` | 47h | 서버 재시작 무관, 멀티 워커 공유 |
| 메모리 (L2) | `_file_api_mem_cache[pdf_path]` | 47h | Redis 장애 시 단독 동작 |
| LRU (소형) | `(pdf_path, mtime_ns)` | 메모리 상주 | 파일 교체 자동 감지 |

**File API URI 만료 처리**: Google File API URI는 **48시간 후 만료**된다. 캐시 TTL을 47시간으로 설정하여 만료 1시간 전에 자동으로 재업로드된다.

**v2.7 메모리 상한 적용 (File API 캐시 계층)**:

| 컨테이너 | 보강 이전 | v2.7 현재 |
|---|---|---|
| `_file_api_mem_cache` | `dict` — 무제한 증가 | `OrderedDict` + LRU eviction (`maxsize=64`) |
| `_upload_locks` | `dict` — 무제한 증가 | `OrderedDict` + unlock된 항목 제거 (`maxsize=128`) |
| Redis 클라이언트 생성 | `aioredis.Redis(...)` 직접 생성 | `redis_manager.get_client()` 공유 커넥션 풀 사용 |

> 서버를 장시간 운영하면 새로운 `pdf_path`가 계속 생겨 두 딕셔너리가 무한히 커지는 메모리 누수가 있었습니다. LRU 방식으로 오래된 항목을 자동 제거하도록 수정하였습니다.

**에이전트 호출 측 변경 사항**: `load_pdf_part`가 `async` 메서드로 변경됨에 따라 모든 호출부에 `await`가 추가되었다.

```python
# v2.6 이전 (동기)
pdf_part = self._bridge.load_pdf_part(pdf_path)

# v2.7 이후 (비동기)
pdf_part = await self._bridge.load_pdf_part(pdf_path)
```

> 영향 파일: `v3/agents/ExplainerAgent.py` (2곳), `v3/agents/GraderAgent.py` (1곳), `v3/agents/QaAgent.py` (2곳)

### 3.3 서브 에이전트 (`ai_agent/v3/agents/`)

모든 에이전트는 동일한 `run_stream / run` 인터페이스를 구현합니다.

| 에이전트 | 역할 | 호출 도구 | 주요 변경 (v2.7) |
|---|---|---|---|
| `ExplainerAgent` | **페이지 단위** 강의 설명 스트리밍 생성 | `EXPLAIN_PAGE` | `chapter_title` → `page_number` 중심으로 전환. 설명 후 "다음 페이지로 넘어갈까요?" 자동 포함 |
| `QaAgent` | 사용자 질문 답변 | `ANSWER_QUESTION` | `load_pdf_part` await 적용 |
| `QuizAgents` | 퀴즈 생성 (`v2/test_gen` 위임) | `GENERATE_QUIZ_*` | `profile`이 `null/{}`/부분 객체여도 기본값 주입+병합 후 검증 (v2.8) |
| `GraderAgent` | MCQ/OX 자동 채점, 단답/서술 LLM 채점 | `AUTO_GRADE_MCQ_OX`, `GRADE_SHORT_OR_ESSAY` | `pdf_path` 파라미터 추가. LLM 채점 시 PDF를 Gemini에 직접 전달. `reason` / `deduction_reason` 필드 추가 |

#### GraderAgent 채점 결과 스키마 (단답/서술형)

```json
{
  "results": [
    {
      "question_index": 0,
      "score": 0.8,
      "passed": true,
      "reason": "핵심 키워드를 포함하고 출제 의도에 맞게 답변하였습니다.",
      "feedback": "핵심 개념을 잘 설명했으나 예시가 부족합니다.",
      "deduction_reason": "구체적인 예시를 들지 않아 0.2점 감점하였습니다."
    }
  ],
  "total_score": 0.8,
  "overall_feedback": "전반적으로 잘 이해하고 있습니다."
}
```

- `score`: 0.0 ~ 1.0 (0.1 단위)
- `passed`: score ≥ 0.6이면 true
- `deduction_reason`: 만점(1.0)이면 빈 문자열 `""`

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

#### EXPLAIN_PAGE 호출 규칙 (v2.6 변경)

v3 세션 흐름에서 `ExplainerAgent`를 호출할 때 `state.current_page` 값을 `page_number`로 자동 전달합니다.

```python
# ToolDispatcher._execute_tool 내부 로직 요약
page_number = state.current_page or 1          # 현재 페이지 번호
chapter_title = page_state.chapter_title        # None이면 ExplainerAgent가 "페이지 N"으로 자동 설정
pdf_path = page_state.pdf_path or state.pdf_path

async for event in self._explainer.run_stream(page_number, pdf_path, chapter_title, detail):
    yield event
```

> `chapter_title`이 없으면 ExplainerAgent가 `"페이지 {page_number}"`를 기본 레이블로 사용합니다.

---

## 4. API 엔드포인트

### 4.1 세션 API (`/api/session`) — 단일 진입점 ★

Spring Boot는 학습 세션의 모든 AI 흐름을 이 단일 진입점으로만 전달합니다.  
어떤 에이전트를 호출할지는 FastAPI OrchestrationEngine이 결정합니다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | `/api/v3/session/by-lecture/{lectureId}` | 세션 조회/생성 |
| `POST` | `/api/v3/session/{sessionId}/event` | 단건 이벤트 (비스트리밍) |
| `POST` | `/api/v3/session/{sessionId}/event/stream` | NDJSON 스트리밍 이벤트 **← 주 진입점** |
| `GET` | `/api/v3/session/{sessionId}/state` | 세션 상태 전체 조회 |
| `DELETE` | `/api/v3/session/{sessionId}` | 세션 삭제 |

#### 이벤트 요청 형식

```json
// 세션 진입 시 (신규 세션이면 lecture_id 필수)
POST /api/v3/session/123/event/stream
{
  "type": "SESSION_ENTERED",
  "lecture_id": 456,
  "payload": {}
}

// 이후 이벤트 (lecture_id 생략 가능 — 세션에 이미 저장됨)
POST /api/v3/session/123/event/stream
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

### 4.2 Bridge API (`/api/v3/bridge`) — 단건 태스크 위임 전용

Spring Boot가 학습 세션 흐름과 무관하게 단건 AI 작업만 필요할 때 사용합니다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| `POST` | `/api/v3/bridge/quiz` | 강의 내용 → 퀴즈 생성 (NDJSON 스트리밍) |
| `POST` | `/api/v3/bridge/grade` | 답안 → 채점 결과 (NDJSON 스트리밍) |
| `POST` | `/api/v3/bridge/quiz/result` | 강의 내용 → 퀴즈 생성 (JSON 직접 반환) |
| `POST` | `/api/v3/bridge/grade/result` | 답안 → 채점 결과 (JSON 직접 반환) |

> **⚠ 금지 패턴**: `/api/v3/bridge/*`를 순서대로 여러 번 호출해 흐름을 구성하는 것은 Spring Boot가 오케스트레이션을 수행하는 것과 동일하므로 허용하지 않습니다.

#### 요청/응답 예시

```json
// 퀴즈 생성
POST /api/v3/bridge/quiz
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
POST /api/v3/bridge/grade
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

### 4.3 v2 Classic API (`/api/v2`)

| 경로 | 설명 |
|---|---|
| `POST /api/v2/lectures/generate` | 개별 강의 설명 생성 (JSON) |
| `POST /api/v2/lectures/generate-stream` | 개별 강의 설명 생성 (NDJSON 스트리밍) |
| `POST /api/v2/qa/evaluate` | QA 답안 평가 |
| `GET  /api/v2/lecture-gen/phase3-5/auto` | 강의 노트 자동 생성 (Phase3~5) |
| `POST /api/v2/test-gen/profile` | 시험 프로필 분석 |
| `POST /api/v2/test-gen/generate` | 시험 문항 생성 |

#### 강의 설명 요청 스키마 (v2.6 변경)

```json
// POST /api/v2/lectures/generate  또는  /generate-stream
{
  "page_number": 3,           // 필수. 현재 사용자가 보고 있는 페이지 번호 (1부터 시작)
  "pdf_path": "uploads/강의.pdf", // 필수. 강의 PDF 파일 경로
  "chapter_title": "2장 소개",   // 선택. 없으면 '페이지 N'으로 자동 설정
  "detail": "NORMAL"           // 선택. "NORMAL" | "DETAILED" (복습 모드)
}
```

> **v2.6 이전 스키마** (`chapter_title` 필수, `md_path` 포함)는 더 이상 지원하지 않습니다.

#### Debate(토론형) 비활성화

`exam_type: "Debate"` 요청은 **현재 지원하지 않습니다**.

| 진입점 | 처리 방식 |
|---|---|
| `POST /api/v2/test-gen/generate` | `exam_type == "DEBATE"` → **HTTP 400** |
| `POST /api/v3/bridge/quiz` | `exam_type == "Debate"` → **HTTP 400** |
| `POST /api/v3/bridge/quiz/result` | `exam_type == "Debate"` → **HTTP 400** |
| v3 세션 흐름 `QUIZ_TYPE_SELECTED` | `quizType == "Debate"` → **SEND_MESSAGE** ("토론형은 현재 지원하지 않습니다. 다른 유형을 선택해 주세요.") + QUIZ_TYPE_PICKER 위젯 재표시 |

> 이전 구현에서는 세션 흐름(`Orchestrator.py`)에서 Debate가 조용히 `Five_Choice`로 fallback됐습니다. v2.7에서는 명확한 안내 메시지를 반환합니다.

### 4.4 공용 유틸 API (버전 없음)

| 경로 | 설명 |
|---|---|
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
| `data` | object? | `done` 이벤트 부가 데이터 (`error`에서도 선택적으로 사용 가능 — 표준 오류 payload) |
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
{"type": "error",       "agent": "system",    "message": "서버 오류가 발생했습니다.", "data": {"type":"error","code":"SERVER_ERROR","message":"Server error","details":[]}}
```

#### `done.data` 필드 상세

> **계약**: 아래 필드명은 Breaking Change 없이 변경되지 않습니다. 상세 스펙은 [`docs/BRIDGE_API.md §5`](BRIDGE_API.md) 참고.

| 상황 | data 내용 |
|---|---|
| 설명 완료 후 퀴즈 제안 | `{"ui": {"widget": "QUIZ_DECISION"}}` |
| 퀴즈 생성 완료 | `{"quiz": [...], "quiz_type": "Five_Choice"}` |
| 채점 완료 (통과) | `{"grading": {"results": [...], "total_score": 0.8, "overall_feedback": "..."}, "passed": true}` |
| 채점 완료 (미통과) | `{"grading": {...}, "passed": false}` |
| 다음 페이지 결정 | `{"ui": {"widget": "NEXT_PAGE_DECISION"}}` |
| 퀴즈 유형 선택 요청 | `{"ui": {"modal": "QUIZ_TYPE_PICKER"}}` |

---

## 6. Redis 키 네임스페이스 컨벤션

Spring Boot / FastAPI 공동 Redis 사용 시 **키 충돌 방지 및 소유권 명확화**를 위해 아래 네임스페이스 규칙을 따릅니다.

### 6.1 접두사 규칙

| 접두사 | 소유자 | 설명 |
|---|---|---|
| `fa:*` | FastAPI 전용 | FastAPI만 읽기/쓰기. Spring은 접근 금지 |
| `sb:*` | Spring Boot 전용 | Spring만 읽기/쓰기. FastAPI는 접근 금지 |
| `shared:*` | 양측 합의 공용 | 양측이 읽기/쓰기 가능. 변경 시 반드시 상호 협의 |

### 6.2 FastAPI 사용 키 목록 (fa:)

| 키 | TTL | 설명 |
|---|---|---|
| `fa:session:{sessionId}` | 24h | 오케스트레이션 세션 전체 상태 (SessionState JSON) |
| `fa:cache:profile:{contentHash}` | 24h | 강의 내용 기반 자동 생성 사용자 프로필 캐시 |
| `fa:result:{sessionId}` | 24h | Phase 5 최종 강의노트 마크다운 결과 |
| `fa:task:{taskId}` | 24h | 비동기 태스크 상태 (PENDING/PROCESSING/DONE/FAILED) |

### 6.3 공용 키 목록 (shared:)

| 키 | TTL | 읽기 | 쓰기 | 설명 |
|---|---|---|---|---|
| `shared:finalized_brief:{sessionId}` | 24h | FastAPI | Spring / FastAPI | 강의 기획안. Spring이 생성하거나 FastAPI Phase 2가 생성 |
| `shared:progress:{sessionId}` | — | Spring (구독) | FastAPI (발행) | Pub/Sub 채널. 강의 생성 진행률 방송 |
| `shared:idem:{requestId}` | 15m | 양측 | 양측 | 중복 요청 방지 멱등성 키 (선택 사항) |

### 6.4 Spring Boot 측 키 (sb:) — 참고용

> FastAPI는 이 키들에 접근하지 않습니다.

| 키 | TTL | 설명 |
|---|---|---|
| `sb:auth:blacklist:{jti}` | 토큰 만료까지 | JWT 블랙리스트 |
| `sb:cache:lecture:{lectureId}` | 5~30m | 강의 메타데이터 캐시 |
| `sb:cache:exam-session:{sessionId}` | 5~30m | 시험 세션 캐시 |
| `sb:task:{taskId}` | 24h | Spring 비동기 작업 상태 |

### 6.5 운영 규칙

- **TTL 필수**: 모든 키는 TTL을 설정합니다. 무한 저장 금지
- **소유권**: 키를 생성한 서비스가 삭제 책임을 집니다
- **공용 키 최소화**: `shared:*` 키는 현재 목록 외 추가 시 양측 합의 필수
- **최대 payload**: 1MB 초과 데이터는 Redis 금지 → DB 또는 파일 저장

---

## 7. 삭제된 레거시 파일 목록

### 7.0 v2.7 패키지 구조 개편 (2026-03-24)

| 삭제된 경로 | 이전 위치 | 현재 위치 |
|---|---|---|
| `ai_agent/agents/` | `ExplainerAgent`, `QaAgent`, `QuizAgents`, `GraderAgent` | `ai_agent/v3/agents/` |
| `ai_agent/engine/` | `StateReducer`, `Orchestrator`, `ToolDispatcher`, `OrchestrationEngine` | `ai_agent/v3/engine/` |
| `ai_agent/LectureContentGenerator/` | 강의 노트 생성 파이프라인 | `ai_agent/v2/note_gen/` |
| `ai_agent/LectureTestGenerator/` | 시험 문항 생성기 | `ai_agent/v2/test_gen/` |
| `ai_agent/Lecture_Agent/component/` | `MainQandAAgent`, `PdfAnalysis` | `ai_agent/v2/legacy/` |
| `ai_agent/common/streaming_schemas.py` | 미사용 레거시 스키마 | 삭제 (대체 없음, `types/domain.py`로 통합됨) |
| `app/models/` | 빈 폴더 | 삭제 |

### 7.1 v2.5~v2.6 리팩터링 (2026-03-12)

| 삭제된 파일 | 대체 항목 |
|---|---|
| `app/routers/delegator.py` (1155줄) | `app/routers/session.py` + `OrchestrationEngine` |
| `app/services/lecture_gen.py` | `ExplainerAgent` 내부 로직 |
| `ai_agent/Lecture_Agent/integration.py` | `OrchestrationEngine` |
| `ai_agent/Lecture_Agent/component/MainLectureAgent.py` | `ExplainerAgent` |

---

## 8. Spring Boot 연동 변경 사항

### 8.1 전체 변경 이력

| 항목 | 변경 전 | 변경 후 |
|---|---|---|
| 강의 이벤트 API | `POST /api/delegator/dispatch` | `POST /api/v3/session/{id}/event/stream` |
| 세션 생성/조회 | 없음 | `GET /api/v3/session/by-lecture/{lectureId}` |
| 단건 퀴즈/채점 (스트리밍) | 없음 | `POST /api/v3/bridge/quiz`, `POST /api/v3/bridge/grade` |
| 단건 퀴즈/채점 (JSON) | 없음 | `POST /api/v3/bridge/quiz/result`, `POST /api/v3/bridge/grade/result` |
| 개별 강의 설명 | 없음 | `POST /api/v2/lectures/generate` |
| 개별 QA 평가 | 없음 | `POST /api/v2/qa/evaluate` |
| 개별 시험 생성 | `/api/test-gen/generate` | `POST /api/v2/test-gen/generate` |
| 강의 노트 생성 | `/api/lecture-gen/*` | `POST /api/v2/lecture-gen/*` |
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
| 채점 결과 필드 (단답/서술) | `feedback`만 존재 | `reason`, `deduction_reason` 필드 추가 (§3.3 참고) |
| 강의 설명 요청 스키마 | `chapter_title`(필수), `md_path` | `page_number`(필수), `chapter_title`(선택) — §4.3 참고 |
| `/bridge/grade` 요청 | `pdf_path` 없음 | `pdf_path` 선택 필드 추가 (단답/서술 채점 정확도 향상) |
| `exam_type: "Debate"` | 처리 시도 | **전체 시험 생성/채점 엔드포인트에서 400 반환** |
| PDF 전달 방식 | `Part.from_bytes()` 항상 사용 | 하이브리드: 15 MB 미만 인라인 / 이상 File API + Redis 캐시 |
| `/bridge/grade`, `/bridge/grade/result` 입력 검증 | 문제 수 ≠ 답안 수 시 zip으로 조용히 truncate | **HTTP 400** 반환 (§9.3 참고) |
| `/api/files/upload` 반환 `path` | 원본 파일명 기반 경로 (`uploads/강의자료.pdf`) | **UUID 기반 경로** (`uploads/{uuid}.pdf`) — `filename` 필드는 원본명 유지 |
| 세션 `lecture_id` 덮어쓰기 | 기존 세션 요청에 다른 `lecture_id`를 보내면 덮어씀 | 기존 세션의 `lecture_id` 우선 사용, 불일치 시 Warning 로그만 기록 |
| Debate 세션 흐름 처리 | 조용히 `Five_Choice`로 fallback | `SEND_MESSAGE` + `QUIZ_TYPE_PICKER` 재표시 (명확한 안내) |

### 8.2 Spring Boot 연동 패턴

```
✅ 허용: v2 Classic — 개별 에이전트 직접 호출 (세션 없음)
  POST /api/v2/lectures/generate   (단건 설명)
  POST /api/v2/qa/evaluate         (단건 QA)
  POST /api/v2/test-gen/generate   (단건 시험 생성)

✅ 허용: v3 통합 — 학습 세션 흐름
  1) GET  /api/v3/session/by-lecture/{lectureId}?pdf_path=... → session_id 수령
  2) POST /api/v3/session/{id}/event/stream  {"type": "SESSION_ENTERED", "lecture_id": 456}
  3) POST /api/v3/session/{id}/event/stream  {"type": "USER_MESSAGE", "payload": {...}}
     ← 이후 요청은 lecture_id 생략 가능

✅ 허용: v3 단건 태스크 위임
  POST /api/v3/bridge/quiz  {"exam_type": "OX_Problem", "lecture_content": "...", "target_count": 5}
  POST /api/v3/bridge/grade {"exam_type": "OX_Problem", "problems": [...],
                             "user_answers": [{"problem_id": 1, "user_response": "O"}]}

❌ 금지: Spring Boot가 bridge를 순서대로 호출해 흐름 구성
  /api/v3/bridge/quiz → 결과 수령 → /api/v3/bridge/grade  (오케스트레이션 금지)
```

> 전체 API 계약 및 `done.data` 스펙 상세: [`docs/BRIDGE_API.md`](docs/BRIDGE_API.md)

---

---

## 9. v2.7 보안 · 안정성 강화 상세

> 레드팀 코드 리뷰 결과를 바탕으로 발견된 취약점 및 잠재 버그를 수정한 내용입니다.  
> 각 항목마다 **왜 문제였는지**, **어떻게 수정했는지**를 기록합니다.

### 9.1 경로 순회(Path Traversal) 취약점 차단

**문제**: `pdf_path` 파라미터를 사용자가 `../../.env` 등의 값으로 보내면 서버 내부 파일에 접근 가능했습니다.  
`app/routers/lecture.py`, `qa.py`, `pdf.py`, `session.py`, `bridge.py` 모두 `pdf_path`를 그대로 에이전트에 전달했습니다.

**수정**: `app/core/path_validator.py` 신규 파일 생성.

```python
# path_validator.py 핵심 로직
UPLOADS_ROOT = pathlib.Path("uploads").resolve()

def validate_pdf_path(pdf_path: str) -> str:
    resolved = (UPLOADS_ROOT / pdf_path).resolve()
    resolved.relative_to(UPLOADS_ROOT)   # uploads/ 외부면 ValueError → 400
    if not resolved.exists():
        raise HTTPException(404, ...)
    return str(resolved)                 # 절대 경로로 정규화하여 반환
```

**적용 범위**:

| 파일 | 적용 함수 |
|---|---|
| `app/routers/lecture.py` | `validate_pdf_path` (generate, generate-stream 양쪽) |
| `app/routers/qa.py` | `validate_pdf_path` (evaluate) |
| `app/routers/pdf.py` | `validate_pdf_path` (analyze) |
| `app/routers/session.py` | `validate_pdf_path_optional` (pdf_path 설정 시) |
| `app/routers/bridge.py` | `validate_pdf_path_optional` (grade, grade/result) |

> **`validate_pdf_path_optional`**: `pdf_path`가 `None` 또는 빈 문자열이면 `None` 반환, 값이 있으면 `validate_pdf_path`와 동일하게 검증합니다.

### 9.2 파일 업로드 파일명 인젝션 차단

**문제**: `file.filename`을 그대로 저장 경로에 사용하면 `../../.env`, `script.py` 등의 이름으로 서버 내부 파일을 덮어쓸 수 있었습니다.

**수정** (`app/routers/upload.py`):

```python
_ALLOWED_SUFFIXES = {".pdf", ".md", ".txt"}     # 허용 확장자 화이트리스트

original_suffix = Path(file.filename).suffix.lower()
if original_suffix not in _ALLOWED_SUFFIXES:
    raise HTTPException(400, ...)

safe_name = f"{uuid.uuid4()}{original_suffix}"  # 원본 파일명 완전 무시, UUID로 저장
dest_path = _UPLOADS_DIR / safe_name
```

**클라이언트 영향**: 응답 JSON에서 `filename`은 원본명(표시용), `path`는 UUID 경로(API 전달용)로 분리됩니다.

```json
{ "filename": "강의자료.pdf", "path": "C:\\...\\uploads\\550e8400-...pdf" }
```

> `path` 값을 그대로 저장했다가 이후 API의 `pdf_path`로 전달하는 방식이면 수정 불필요. 파일명을 파싱해 사용한다면 `filename` 필드 사용으로 변경 필요.

### 9.3 채점 입력 길이 불일치 방어

**문제**: `problems` 배열과 `user_answers` 배열의 길이가 다를 때 Python `zip`이 짧은 쪽 기준으로 조용히 truncate하여 일부 문항이 채점되지 않고 통과될 수 있었습니다.

**수정 위치**: `app/routers/bridge.py` (`/grade`, `/grade/result` 양쪽), `ai_agent/v3/agents/GraderAgent.py` (`_grade_auto`) 이중 방어.

```python
# bridge.py — HTTP 계층에서 조기 차단
if len(req.problems) != len(req.user_answers):
    raise HTTPException(400, f"문제 수({len(req.problems)})와 답안 수({len(req.user_answers)})가 일치하지 않습니다.")

# GraderAgent._grade_auto — 에이전트 계층 방어선
if len(problems) != len(user_answers):
    raise ValueError(...)
```

### 9.4 세션 `lecture_id` IDOR(불필요한 직접 객체 참조) 방지

**문제**: 기존 세션에 다른 `lecture_id`를 보내면 세션 소유자의 강의 ID가 변경될 수 있었습니다. 세션 ID를 아는 사용자가 타인 세션의 강의 컨텍스트를 변경하는 공격이 가능했습니다.

**수정** (`app/routers/session.py` — `_resolve_lecture_id`):

```python
state = await session_store.get(session_id)
if state is not None:
    if req.lecture_id is not None and req.lecture_id != state.lecture_id:
        logger.warning("lecture_id mismatch: session=%d has %d, request sent %d — using session value",
                        session_id, state.lecture_id, req.lecture_id)
    return state.lecture_id   # ← 항상 세션 저장 값 우선
```

> 에러는 반환하지 않고 서버 Warning 로그만 남깁니다. 기존 클라이언트가 `lecture_id`를 습관적으로 포함해도 동작에 지장이 없습니다.

### 9.5 `MainQandAAgent.py` — `asyncio.run()` 중첩 이벤트 루프 문제

**문제**: `asyncio.run()`은 이미 이벤트 루프가 실행 중인 컨텍스트(예: `asyncio.to_thread` 내부)에서 호출하면 `RuntimeError: This event loop is already running` 오류가 발생합니다.

**수정** (`ai_agent/v2/legacy/MainQandAAgent.py`):

```python
# 이전
asyncio.run(run_qa_flow_async(...))

# v2.7
loop = asyncio.new_event_loop()
try:
    return loop.run_until_complete(run_qa_flow_async(...))
finally:
    loop.close()
```

---

## 10. 알려진 미처리 이슈

> 현재 구현에서 인지하고 있으나 아직 수정되지 않은 항목입니다.

### 10.1 전체 API 인증/인가 없음 `[CRITICAL]`

**현상**: 모든 엔드포인트(`/api/v2/*`, `/api/v3/*`, `/api/files/*`)에 토큰 검증 로직이 없습니다.  
**영향**: URL을 아는 누구나 세션 생성, 파일 업로드, 채점 요청 가능.  
**해결 방향**: Spring Boot가 발급하는 JWT를 FastAPI가 검증하는 의존성(Dependency) 추가 필요. Spring Boot 팀과 토큰 검증 방식 합의 선행 필요.

### 10.2 `StateReducer.py` — QUIZ 상태 롤백 불가 `[MEDIUM]`

**현상**: `QUIZ_SUBMITTED` 이벤트 수신 즉시 세션 상태를 `QUIZ_GRADED`로 선반영합니다.  
이후 `GraderAgent` 채점이 실패하더라도 상태가 `QUIZ_GRADED`로 남아 세션이 오염됩니다.

```
QUIZ_SUBMITTED 수신
    → 상태: QUIZ_GRADED 선반영  ← 문제
    → GraderAgent 채점 시작
    → (채점 실패 시 상태는 그대로 QUIZ_GRADED)
```

**해결 방향**: 채점 성공 후 상태를 업데이트하거나 `QUIZ_GRADING_IN_PROGRESS` 중간 상태를 도입.

---

## 11. 확장 포인트

설계서 §13 기준 및 v2.7 보강 항목:

| 확장 항목 | 방법 |
|---|---|
| 신규 서브 에이전트 추가 | `ToolName` Enum 추가 → `Orchestrator` 분기 추가 → `ToolDispatcher._execute_tool` 케이스 추가 |
| 정책 고도화 | `Orchestrator._should*` 함수를 별도 정책 모듈로 분리 |
| LLM 힌트 연동 | `OrchestrationEngine`에서 Gemini 호출 후 `Orchestrator.run(llm_hint=...)` 주입 |
| 저장소 교체 | `SessionStore` 인터페이스를 유지하며 Redis → DB로 교체 |
| 상태 머신 강화 | `PageStatus` 전이를 명시적 검증기(guard)로 강제 |
| 소형 PDF LRU 크기 조정 | `GeminiBridgeClient._load_bytes_cached` `lru_cache(maxsize=N)` 값 변경 |
| 소형/대형 PDF 임계값 조정 | `PDF_INLINE_MB` 환경변수 설정 (기본 15 MB) |
| File API → Part.from_bytes 전환 | `PDF_INLINE_MB=9999` 설정으로 모든 파일을 인라인 처리 |
| 타임아웃 조정 | `GEMINI_STREAM_TIMEOUT` 환경변수 설정 (기본 300초) |
| 스트리밍 Queue 크기 조정 | `GeminiBridgeClient.stream()` `asyncio.Queue(maxsize=N)` 값 변경 (기본 200) |
| LRU 메모리 캐시 상한 조정 | `_MEM_CACHE_MAX=64`, `_LOCK_CACHE_MAX=128` 상수 변경 |
| 업로드 허용 파일 형식 추가 | `app/routers/upload.py` `_ALLOWED_SUFFIXES` 집합에 추가 |
| PDF path 허용 디렉토리 변경 | `app/core/path_validator.py` `UPLOADS_ROOT` 경로 변경 |

---

## 12. 환경변수 목록

| 변수명 | 기본값 | 설명 |
|---|---|---|
| `GEMINI_API_KEY` | (필수) | Google Gemini API 키 |
| `REDIS_HOST` | `localhost` | Redis 호스트 |
| `REDIS_PORT` | `6379` | Redis 포트 |
| `GEMINI_STREAM_TIMEOUT` | `300` | Gemini API 타임아웃 (초) |
| `GEMINI_HEARTBEAT_INTERVAL` | `10` | 스트리밍 heartbeat 간격 (초) |
| `PDF_INLINE_MB` | `15` | 이 크기 미만 PDF는 인라인 전송, 이상은 File API 업로드 (MB 단위) |
