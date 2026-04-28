# learning 도메인 (v3 학습 세션)

FastAPI `MergeEduAgent` / `OrchestrationEngine` 과 연동되는 **v3 학습 세션** 프록시 도메인.
이 도메인 작업 시 참고용 상시 메모. 버그·성능 수정 **이력** 은 루트 `uou-capstone/DEV_NOTES.md` 참조.

---

## 개요

- **Spring Boot 는 얇은 프록시**. 세션 상태·오케스트레이션 로직은 전부 FastAPI(`llm_multi_agent` 브랜치)가 보유. Spring 측은 **인증/인가 + 강의-PDF 매핑 + NDJSON↔SSE 변환** 만 담당.
- **DB 엔티티 없음**. 세션 영속은 FastAPI/Redis 가 책임.
- v2 시험 생성 API(`/api/exams/*`) 와 완전히 독립 — 외부 호환성 영향 없음.
- 역할: STUDENT/TEACHER 둘 다 사용. `@PreAuthorize("hasAuthority('STUDENT') or hasAuthority('TEACHER')")`.

---

## 주요 플로우

prefix: `/api/learning/sessions`

### 1. 세션 조회/생성
- `POST /{lectureId}?pdfPath=&sessionId=`
- 내부: `FastApiSessionClient.getOrCreateByLecture(lectureId, pdfPath, sessionId)` → `GET /api/v3/session/by-lecture/{lectureId}`
- `pdfPath` 누락 시 `materialRepository.findFirstByLecture_IdAndMaterialTypeOrderByCreatedAtDesc(lectureId, "PDF")` 로 강의 최신 PDF 자동 조회. 없으면 `null` 그대로 FastAPI 에 전달 (PDF 없는 세션 처리)
- 응답: `{ session_id, lecture_id, current_page, ai_status_connected, ... }` (FastAPI 본문 그대로 통과)

### 2. 이벤트 SSE 스트리밍
- `POST /{sessionId}/event` (Content-Type: `application/json` → 응답 `text/event-stream`)
- 쿼리: `lectureId` (신규 세션 직후만 필수), `page` / `pageNumber` / `currentPage` (1-based, FastAPI `current_page` 동기화)
- 본문(`SessionEventRequest`): `type` + `@JsonAnySetter` 로 임의 필드 자유 추가 → 그대로 FastAPI 페이로드로 변환
- 내부: `FastApiSessionClient.streamEvent(sessionId, body)` → `POST /api/v3/session/{sessionId}/event/stream` (NDJSON 라인 스트림)
- NDJSON → SSE 변환: 빈 라인·heartbeat 제외(`NdjsonLineFilters.isHeartbeatLine`) 후 라인을 `event: message` 의 data 로 그대로 래핑

#### FastAPI 페이로드 빌드 규칙 (`buildPayloadForFastApi`)
1. **이중 래핑 평탄화** — FE가 `{ "type":"X", "payload":{...} }` 로 보내면 `payload` 안쪽을 펼침
2. **USER_MESSAGE 호환** — 구버전 클라이언트가 `text` 만 보내면 `question` 으로 복사 (Bridge·Session 계약은 `question`)
3. **viewer page 주입** — `current_page` / `page` / `pageNumber` 세 키 모두에 동일 값 (FastAPI 측 키 명칭 다양성 대응)

#### 지원 이벤트 타입
`SESSION_ENTERED`, `START_EXPLANATION_DECISION`, `PAGE_CHANGED`, `USER_MESSAGE`, `QUIZ_DECISION`, `QUIZ_TYPE_SELECTED`, `QUIZ_SUBMITTED`, `REVIEW_DECISION`, `RETEST_DECISION`, `NEXT_PAGE_DECISION`, `SAVE_AND_EXIT`
> 위 목록은 `SessionEventRequest` Javadoc 기준. 실제 검증은 FastAPI 가 수행 — Spring 은 string 만 비어있지 않으면 통과시킴.

#### SSE 응답 (NDJSON 그대로 통과)
| 이벤트 (line type) | 예시 |
|---|---|
| `agent_delta` | `{"type":"agent_delta","agent":"explainer","delta":"..."}` |
| `done` | `{"type":"done","agent":"explainer","tool":"EXPLAIN_PAGE","final":true,"data":{}}` |
| `error` | `{"type":"error","message":"..."}` |

응답 헤더: `X-Accel-Buffering: no`, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff` — nginx/프록시 버퍼링 차단.

---

## 외부 의존

- **FastAPI v3 (`llm_multi_agent`)**
  - `GET  /api/v3/session/by-lecture/{lectureId}` (단건, `aiServiceWebClient`)
  - `POST /api/v3/session/{sessionId}/event/stream` (NDJSON, `aiServiceStreamingWebClient` — 별도 WebClient)
- **클라이언트**: `integration/fastapi/FastApiSessionClient`
- **WebClient 두 개 분리** — 동기 응답용 / 스트리밍용. 스트리밍에는 maxInMemorySize·timeout 다른 설정 적용. WebClient 빈 정의는 `config/WebClientConfig.java`.

---

## 상시 주의사항 (Gotcha)

### Spring 은 비즈니스 로직을 추가하지 말 것
이 도메인은 의도적으로 **얇은 프록시**. 세션 상태 추적·재시도·캐싱 등은 FastAPI 측에 추가하고, Spring 은 페이로드 정규화(이중 래핑 제거 / `text→question` / page 주입) 정도까지만 유지.

### `EventSource` 사용 금지 — `fetch + ReadableStream`
- `POST` + `Authorization` 헤더 필요 → 브라우저 `EventSource` 는 **GET 전용 + 임의 헤더 불가**
- FE 는 `fetch` 응답을 `body.getReader()` 로 라인 파싱 (CLAUDE.md 의 SSE 가이드 참조)

### `SessionEventRequest` 의 extra 필드 동작
`@JsonAnySetter` / `@JsonAnyGetter` 로 수집한 임의 키들은 `toPayload()` 가 그대로 반환 → FastAPI 로 직행. 필드 이름 검증/필터링 없음. **민감정보 주입 금지** (FastAPI 로그·캐시에 적재될 수 있음).

### NDJSON 라인 분리는 WebClient 가 처리
`bodyToFlux(String.class)` 가 라인 단위로 끊어줌. `NdjsonLineFilters.isHeartbeatLine` 으로 keep-alive 라인만 추가 필터.

### 페이지 파라미터 별칭
컨트롤러는 `page` / `pageNumber` / `currentPage` 셋 다 받음. `firstNonNullPositive(currentPage, pageNumber, page)` 우선순위 — `currentPage` 가 가장 강한 신호로 가정. FE 마이그레이션 중에는 어느 키든 동작.

### SSE 응답 본문 캐릭터셋
`text/event-stream` 에는 charset 미명시. 라인 데이터에 비-ASCII 가 있으면 클라이언트가 UTF-8 가정. FastAPI 는 UTF-8 로 출력하므로 기본값 일치 — 변환 없이 통과.

---

## 주요 파일

### Controller
- `controller/LearningSessionController.java` — `/api/learning/sessions` REST + SSE

### Service
- `service/LearningSessionService.java` — FastAPI 프록시 (204줄). 페이로드 정규화·SSE 변환·에러 매핑

### DTO
- `dto/SessionEventRequest.java` — `type` + 임의 필드(`@JsonAnySetter`)

### 외부 통신
- `integration/fastapi/FastApiSessionClient.java` — v3 세션 단건 조회 + 이벤트 NDJSON 스트림
- `util/NdjsonLineFilters.java` — heartbeat 라인 판별
- `util/BridgeResponseLogger.java` — FastAPI 응답 디버그 로그 표준화

### 의존 (다른 도메인)
- `material.repository.MaterialRepository` — `findFirstByLecture_IdAndMaterialTypeOrderByCreatedAtDesc` (PDF 자동 조회)

---

## 관련 문서

- `uou-capstone/FRONTEND_V2_V3_API.md` — v3 Bridge·Session 계약 (FE-BE 약속의 정본)
- `uou-capstone/V27_E2E_SMOKE_TEST.md` — B-2 (Learning Session SSE 스모크 시나리오)
