# agent

AI Agent 추상화 layer. FastAPI 측 Agent 엔드포인트를 Spring Bean 으로 감싸서, 도메인 서비스가 일관된 인터페이스로 호출하도록.

> ℹ️ **v3 전환 후 exam Agent (13개) 는 FastAPI v3 Bridge·Session 위임으로 대체되어 삭제됨**. 현재 살아있는 Agent 는 **material Agent 8개** 만. `agent/exam/debate/` 디렉토리는 빈 상태로 보존.

---

## 인터페이스 (`Agent.java`)

모든 Agent 는 3가지 실행 모드를 제공:

```java
<T> T execute(AgentRequest request, Class<T> responseType);            // 동기
<T> Mono<T> executeAsync(AgentRequest request, Class<T> responseType); // 비동기
Flux<StreamingEvent> executeStreaming(AgentRequest request);           // SSE 스트리밍
```

`AgentRequest` 는 `prompt: String` + `context: Map<String,Object>` 만 정의 — 각 Agent 가 `buildRequestBody` 로 자기 형식에 맞춰 변환.

---

## 공통 구현 — `AbstractAgent`

`base/AbstractAgent` 가 모든 Agent 의 공통 동작 보유:
- WebClient 호출 (`aiServiceWebClient`)
- 응답 파싱 (`String → ObjectMapper.readValue`)
- 성능 로깅 (`AgentPerformanceLogger.startExecution/endExecution`)
- **재시도** — 503 / 429 / `RESOURCE_EXHAUSTED` / `quota` / `high demand` 메시지에 한해 **3회 backoff (2s → 10s)**
- **재시도 불가능 에러는 즉시 종료** + `StreamingEvent { type:"error", delta:"..." }` 1건 발행 후 스트림 종료

서브클래스는 두 메서드만 구현:
```java
protected abstract String getEndpoint();             // FastAPI 경로
protected abstract Object buildRequestBody(AgentRequest request);
```

스트리밍 호출 시 자동으로 `getEndpoint() + "/stream"` 으로 매핑.

---

## material Agent — 5-Phase 매핑

| Agent | Phase | FastAPI 엔드포인트 | 용도 |
|---|---|---|---|
| `PlanningAgent` | Phase 1 | `POST /api/v2/lecture-gen/phase1/planning` | 키워드 → DraftPlan |
| `ConfirmAgent` | Phase 2 | `POST /api/v2/lecture-gen/phase2/confirm` | 사용자 피드백 반영 → FinalizedBrief |
| `UpdateAgent` | Phase 2 보조 | `POST /api/v2/lecture-gen/phase2/update` | 기획안 부분 갱신 |
| `DecompositionAgent` | Phase 3 | `POST /api/v2/lecture-gen/phase3/decomposition` | 챕터 분해 |
| `WriteAgent` | Phase 3 | `POST /api/v2/lecture-gen/phase3/write` | 본문 작성 |
| `ValidationAgent` | Phase 4 | `POST /api/v2/lecture-gen/phase4/validation` | 검증 |
| `ReviewAgent` | Phase 4 | `POST /api/v2/lecture-gen/phase4/review` | 리뷰 |
| `EditorAgent` | Phase 5 | `POST /api/v2/lecture-gen/phase5/editor` | 최종 문서 조립 |

Phase 3-5 자동 흐름은 `material.generation.MaterialGenerationService.processPhase3To5Async` 에서 위 Agent 를 직접 호출하지 않고, `FastApiNoteGenClient.startPhase3To5Auto` (백그라운드 시작) + 폴링으로 처리.

> Phase 1·2 동기 흐름과 SSE 흐름에서는 위 Agent 를 직접 호출.

---

## DTO

- **`StreamingEvent`** — `{ type:"thought"|"answer"|"error", delta, content:ThoughtNode, metadata }`
- **`ThoughtNode`** — `{ stepId, content, status, visualType, vizType, vizData, timestamp }` — Mermaid.js / KaTeX 등 시각화 정보 포함
- **`AgentRequest`** — 인터페이스. 각 도메인이 자체 구현 (`SimpleAgentRequest` 등)

스트리밍 응답 본문은 FastAPI 측이 `StreamingEvent` 형식 JSON 을 NDJSON 으로 발행 — `bodyToFlux(StreamingEvent.class)` 로 직접 디코드.

---

## Bean 등록 — `config/AgentConfig`

8개 material Agent 를 모두 `@Bean` 으로 노출. `aiServiceWebClient` (스트리밍 아닌 일반 빈) + `ObjectMapper` + `AgentPerformanceLogger` 주입.

```java
@Bean public PlanningAgent planningAgent() { ... }
@Bean public ConfirmAgent confirmAgent()   { ... }
// ... 8개 ...
```

도메인 서비스(`MaterialGenerationService`) 가 필드로 주입받아 사용.

---

## 예외

- `exception/AgentExecutionException` — `BusinessException(CommonErrorCode.AGENT_EXECUTION_FAILED)` 래퍼
- `exception/StreamingException` — `BusinessException(CommonErrorCode.STREAMING_FAILED)` 래퍼

> ⚠️ **현재 `AbstractAgent` 는 위 예외를 직접 던지지 않고 `RuntimeException` 던짐** (`map(rawResponse -> ... throw new RuntimeException("Failed to parse FastAPI response", e))`). CLAUDE.md 규칙(`RuntimeException 금지 → BusinessException`) 위반 — 정리 후보.

---

## 상시 주의사항 (Gotcha)

### exam Agent 디렉토리는 비어있음 — 새로 만들지 말 것
`agent/exam/debate/` 는 v2 토론 Agent 코드가 있던 자리. v3 전환 후 FastAPI v3 Bridge/Session 으로 이전. **새 시험 Agent 는 이 패키지에 추가하지 말고**, `domain/exam/service/DebateService` 같은 도메인 서비스에서 `FastApiSessionClient` 를 통해 호출.

### `aiServiceWebClient` 사용 — 스트리밍은 자동으로 `/stream` 분기
`AbstractAgent.executeStreaming` 이 베이스 엔드포인트에 `/stream` 붙여 호출하지만 WebClient 는 일반 빈을 사용 (`aiServiceStreamingWebClient` 아님). 긴 스트림에서 maxInMemorySize 초과 시 에러 — 큰 응답이 예상되면 스트리밍 빈으로 교체 검토.

### `getEndpoint()` 가 String 상수 반환 — 환경변수화 안 됨
경로가 코드 하드코딩. FastAPI 측 경로 변경 시 모든 Agent 클래스 수정 필요. 분리하려면 `@Value("${agent.<name>.endpoint}")` 로 변경 가능.

### 재시도 메시지 매칭이 영문/한글 혼합
`AbstractAgent.executeStreaming` 의 retry filter 가 `"503"`, `"429"`, `"RESOURCE_EXHAUSTED"`, `"quota"`, `"high demand"`, `"일시적으로 사용할 수 없습니다"`, `"할당량이 초과되었습니다"` 등 메시지 문자열 매칭. 메시지 형식이 바뀌면 재시도 로직이 동작 안 함 — 정기 점검.

### 동기 `execute` 는 `executeAsync(...).block()`
즉 `@Transactional` 안에서 호출하면 DB 커넥션 점유 + WebClient 블로킹 (CLAUDE.md 금지 사항). 호출 측에서 트랜잭션 분리 필수.

### `StreamingEvent` 타입 문자열
`"thought"` / `"answer"` / `"error"` 외 신규 타입 도입 시 FE 측 매핑도 함께 갱신. 컨트롤러(`ExamGenerationStreamController`) 가 그대로 SSE 데이터로 흘림.

---

## 주요 파일

### 인터페이스 / 공통
- `Agent.java` — 인터페이스 (execute / executeAsync / executeStreaming)
- `AgentRequest.java` — 요청 인터페이스 (prompt + context)
- `StreamingEvent.java` — 스트리밍 이벤트 DTO
- `ThoughtNode.java` — 추론 단계 + 시각화 메타
- `base/AbstractAgent.java` — 공통 구현 (재시도·로깅 포함)

### Material Agent (8개)
- `material/PlanningAgent.java` — Phase 1
- `material/ConfirmAgent.java`, `material/UpdateAgent.java` — Phase 2
- `material/DecompositionAgent.java`, `material/WriteAgent.java` — Phase 3
- `material/ValidationAgent.java`, `material/ReviewAgent.java` — Phase 4
- `material/EditorAgent.java` — Phase 5

### Exam Agent
- `exam/debate/` — **빈 디렉토리** (v3 전환으로 코드 제거됨)

### 예외
- `exception/AgentExecutionException.java`
- `exception/StreamingException.java`

### Bean 등록
- `config/AgentConfig.java` — 8개 material Agent 빈 등록 (도메인 외부)

### 의존
- `service/AgentPerformanceLogger.java` — 시작/종료 시간 로깅
- `config/WebClientConfig.aiServiceWebClient` — FastAPI 호출용
- `common/error/CommonErrorCode.{AGENT_EXECUTION_FAILED, STREAMING_FAILED}`
