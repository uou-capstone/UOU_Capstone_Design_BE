# integration/fastapi

FastAPI(`llm_multi_agent` 브랜치) 호출 단일 진입점. 도메인 서비스에서 `WebClient` 디테일을 숨기고, **엔드포인트 경로를 이 패키지에만** 두기 위한 layer.

---

## 클라이언트 ↔ FastAPI 엔드포인트 매핑

| 클라이언트 | 메서드 | FastAPI 경로 | 호출 도메인 |
|---|---|---|---|
| `FastApiSessionClient` | `getOrCreateByLecture(lectureId, pdfPath, sessionId)` | `GET /api/v3/session/by-lecture/{lectureId}?pdf_path=&session_id=` | `learning` |
| `FastApiSessionClient` | `streamEvent(sessionId, body)` (NDJSON Flux) | `POST /api/v3/session/{sessionId}/event/stream` | `learning` |
| `FastApiSessionClient` | `callEvent(sessionId, body)` (단건) | `POST /api/v3/session/{sessionId}/event` | `exam.DebateService` |
| `FastApiNoteGenClient` | `startPhase3To5Auto(body)` | `POST /api/v2/lecture-gen/phase3-5/auto` | `material.generation` |
| `FastApiNoteGenClient` | `getStatusByUrl(statusUrl)` | (응답 `status_url` 동적 GET) | `material.generation` |
| `FastApiBridgeClient` | `testGenGenerate(body)` | `POST /api/v2/test-gen/generate` | `exam.ExamGenerationService` |
| `FastApiBridgeClient` | `quizResult(body)` | `POST /api/v3/bridge/quiz/result` | (예약) |
| `FastApiBridgeClient` | `gradeResult(body)` | `POST /api/v3/bridge/grade/result` | (예약) |
| `FastApiBridgeClient` | `streamQuiz(body)` (Flux) | `POST /api/v3/bridge/quiz` | (예약) |
| `FastApiQaClient` | `evaluate(qaRequest)` | `POST /api/v2/qa/evaluate` | `inquiry` |
| `FastApiDelegatorClient` | `dispatchGenerateContentAsync(req, secret)` | `POST /api/v2/lectures/generate-stream` (fire-and-forget) | `course.lecture.LegacyLectureFlowService` |
| `FastApiDelegatorClient` | `streamLectureContent(payload, secret)` (Flux) | `POST /api/v2/lectures/generate-stream` | `course.lecture.LegacyLectureFlowService` |
| `FastApiDelegatorClient` | `dispatchStage(req, secret)` | (legacy stage shim — 내부 분기) | `course.lecture.LegacyLectureFlowService` |
| `FastApiDelegatorClient` | `callLectureGenerate(payload, secret)` | `POST /api/v2/lectures/generate-stream` (집계 후 단건) | `course.lecture.LegacyLectureFlowService` |

> 새 FastAPI 엔드포인트 추가 시 **반드시 이 패키지에 메서드 추가** — 도메인 서비스가 직접 `WebClient` 호출하지 말 것.

---

## WebClient 두 종류 — 동기 vs 스트리밍

`config/WebClientConfig` 가 두 빈을 정의:

- **`aiServiceWebClient`** — 집계 응답용 (`bodyToMono`). 일반 timeout, maxInMemorySize 10MB
- **`aiServiceStreamingWebClient`** — NDJSON/SSE 스트리밍 전용 (`bodyToFlux`). HTTP/1.1 강제, 256KB 버퍼, 긴 read timeout

스트리밍 호출은 **반드시 `aiServiceStreamingWebClient`** 사용. 일반 클라이언트로 NDJSON 받으면 connection pool 점유 + 타임아웃 이슈.

---

## NDJSON 라인 처리 표준

FastAPI 측은 NDJSON (한 줄에 JSON 한 개) 으로 스트림 발행. 처리 표준:

```java
.bodyToFlux(String.class)
.filter(line -> !line.isBlank())
.filter(line -> !NdjsonLineFilters.isHeartbeatLine(objectMapper, line))
.map(this::parseLine)
.filter(Objects::nonNull);
```

- **빈 라인 제거**
- **heartbeat 라인 제거** — `util.NdjsonLineFilters.isHeartbeatLine` 가 `{"type":"heartbeat"}` 같은 keep-alive 만 식별
- **파싱 실패 라인은 null 반환** 후 필터로 제거 (스트림 중단 X)

---

## 사고(Thought) vs 본문(Main) 분리 — `LectureStreamChunk`

`FastApiDelegatorClient.parseLectureStreamLine` 이 NDJSON 한 줄 → `LectureStreamChunk(THOUGHT|MAIN, delta)` 로 분류. SSE 측에서는 `event: thought` / `event: message` 로 매핑.

**THOUGHT 로 분류되는 신호 (any):**
- `type` 이 `thought_delta` / `thinking_delta` / `reasoning_delta`
- `type=agent_delta` 이고 `channel`/`agent` 가 사고 별칭 중 하나 (`thinking`, `reasoning`, `thought`, `internal`, `think` 포함 등 — `THOUGHT_CHANNEL_ALIASES` 참조)
- `phase` 또는 `role` 에 `think`/`reason`/`internal` 포함
- `contentType=THOUGHT`

**MAIN:** 위 외 `agent_delta` (delta 비어있지 않은 경우)

> Gemini 모델/FastAPI 변경에 따라 라인 형식이 자주 바뀜 → `parseLectureStreamLine` 의 분기를 정기적으로 점검.

---

## 공통 헤더

`FastApiDelegatorClient.applyCommonHeaders` 는:
- `ngrok-skip-browser-warning: true` (개발환경 ngrok 우회)
- `X-AI-SECRET-KEY` (선택) — `${ai.service.secret-key}` 환경변수에서 주입. v1 callback 인증용. v3 경로는 secret 없이 호출

`FastApiQaClient.evaluate` 도 `ngrok-skip-browser-warning` 만 추가.

---

## 에러 매핑 패턴

각 클라이언트 공통:
```java
.onErrorMap(e -> new BusinessException(CommonErrorCode.AI_SERVER_ERROR,
        "<용도> 서비스 호출 실패: " + e.getMessage()))
```
또는 `FastApiDelegatorClient` 처럼 `4xx`/`5xx` 분기 → `StreamingApiException`. 도메인 서비스는 `BusinessException` 만 처리하면 충분 — `RuntimeException` 던지지 않음 (CLAUDE.md 규칙).

`FastApiNoteGenClient` 만 응답 본문(`responseBody`) 까지 포함해 상세 메시지 — 디버그용.

---

## 상시 주의사항 (Gotcha)

### 트랜잭션 밖 호출
`@Transactional` 안에서 `WebClient.block()` 금지 (CLAUDE.md). 이 패키지의 동기 메서드(`testGenGenerate`, `evaluate`, `getStatusByUrl` 등) 는 모두 `.block()` 사용 — **호출 측에서 트랜잭션 경계 분리** 필요. 예: `material.MaterialService.uploadFile` 가 WebClient 를 트랜잭션 밖에서 먼저 실행하고 DB 기록만 별도 `@Transactional` 메서드로.

### `block()` 스레드풀 격리 (DEV_NOTES 2-5)
현재 `block()` 호출이 caller 스레드를 점유. async executor (`materialGenerationExecutor`, `taskExecutor` 등) 점유 시 풀 포화 가능. 후속 작업: 전용 `BoundedElasticScheduler` 도입.

### `dispatchStage` 는 v1 shim
`FastApiDelegatorClient.dispatchStage(stage, payload)` 는 옛 `/api/delegator/dispatch` 호출 시그니처를 v2 lectures 엔드포인트로 변환하는 호환 layer. 신규 코드 사용 금지 — `streamLectureContent` 또는 단건 v2/v3 API 사용.

### `LectureStreamChunk` 는 v2 lectures 전용
v3 (`learning` 도메인) 은 NDJSON 라인을 그대로 SSE 로 흘림 (분류 안 함). 라인 type 별 처리는 FE 가 담당. v2 만 `THOUGHT/MAIN` 분리 필요.

### snake_case ↔ camelCase
FastAPI 는 snake_case (`pdf_path`, `lecture_id`, `current_page` ...). 빌드 시 `Map<String,Object>` 직접 구성 또는 `ObjectMapper.copy().setPropertyNamingStrategy(SNAKE_CASE)` 1회용. 전역 매퍼 변경 금지.

### `ngrok-skip-browser-warning` 헤더 prod 영향
ngrok 만 사용. prod 에선 무시 — 헤더 추가 자체는 비용 없음. 굳이 분기 안 해도 됨.

### v3 Bridge 미사용 메서드들
`FastApiBridgeClient.{quizResult, gradeResult, streamQuiz}` 는 현재 호출 위치 0. 향후 v3 단건 시험 흐름 도입 시 사용 예정 — 지우지 말 것.

### Heartbeat 형식
`{"type":"heartbeat"}` 외 다른 keep-alive 형식이 도입되면 `NdjsonLineFilters.isHeartbeatLine` 갱신 필요. 현재 단순 매칭.

---

## 주요 파일

- `FastApiSessionClient.java` — v3 세션 (학습)
- `FastApiNoteGenClient.java` — v2 자료 생성 Phase 3~5
- `FastApiBridgeClient.java` — v2 시험 생성 + v3 Bridge 예약 메서드
- `FastApiQaClient.java` — v2 QA 평가
- `FastApiDelegatorClient.java` — v1 lecture 흐름 shim + NDJSON 분류 (legacy)
- `LectureStreamChunk.java` — v2 lecture NDJSON → THOUGHT/MAIN 구분 record

### 의존 (다른 인프라)
- `config/WebClientConfig.java` — `aiServiceWebClient`, `aiServiceStreamingWebClient` 빈
- `util/NdjsonLineFilters.java` — heartbeat 판별
- `util/BridgeResponseLogger.java` — debug 로그 표준화
- `common/error/CommonErrorCode.AI_SERVER_ERROR` / `AI_CONTENT_GENERATION_FAILED`
- `course/lecture/exception/StreamingApiException.java` — delegator 4xx/5xx 매핑
