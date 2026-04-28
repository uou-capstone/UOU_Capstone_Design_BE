# config

Spring Boot 빈/필터/인터셉터/예외 핸들러 정의 모음. 도메인 외부의 **인프라 wiring**.

---

## 파일 카탈로그

| 파일 | 역할 |
|---|---|
| `SecurityConfig` | Spring Security `FilterChain` + CORS + OAuth2 + JWT 필터 조립 |
| `WebClientConfig` | FastAPI 호출용 WebClient 빈 2개 (집계 / 스트리밍) |
| `WebMvcConfig` | 인터셉터 등록 + MVC async executor |
| `AsyncConfig` | `@Async` 용 Executor 3개 + RejectedExecution 정책 |
| `RedisConfig` | StringRedisTemplate / RedisTemplate / RedissonClient / Pub/Sub 리스너 컨테이너 |
| `AgentConfig` | material Agent 8개 빈 등록 (`agent` README 참조) |
| `OpenApiConfig` | Swagger UI Bearer 인증 스키마 |
| `GlobalExceptionHandler` | `@RestControllerAdvice` — 표준 에러 응답 매핑 |
| `RateLimitInterceptor` | 인증 사용자 호출 제한 (이메일 키) |
| `AuthRateLimitInterceptor` | 미인증 인증 엔드포인트 IP 제한 (브루트포스 방어) |

---

## SecurityConfig — FilterChain 조립

```
[CORS preflight (OPTIONS)] → permit
[JwtAuthenticationFilter] (BasicAuthenticationFilter 전에 추가)
  ↓ 토큰 검증 + 블랙리스트 체크
[SecurityFilterChain]
  - OAuth2 로그인 (Kakao) — userInfoEndpoint = CustomOAuth2UserService
  - successHandler / failureHandler
  - exceptionHandling: RestAuthenticationEntryPoint (401), JwtAccessDeniedHandler (403)
  - sessionCreationPolicy: STATELESS
```

### 공개 경로
```
/api/auth/**
/api/health, /actuator/health
/api/ai/callback/**     (AI 콜백 — secret 헤더로 자체 인증)
/error
/swagger-ui.html, /api-docs/**, /swagger-ui/**
/login/**, /oauth2/**
DispatcherType.ASYNC, ERROR (SSE async dispatch 우회)
```

### CORS
- 기본 origin: `localhost:3000/5173/8000` + `127.0.0.1:*`
- **추가 origin** 은 `${CORS_ALLOWED_ORIGINS}` 환경변수로 (`,` 구분). prod 에서 `https://ai-lms.netlify.app` 같이 설정
- `allowCredentials=true` — 쿠키/Authorization 함께 허용
- preflight 캐시 1시간

> ⚠️ **CORS origin 하드코딩 금지** (CLAUDE.md). 신규 환경 추가 시 `extraOrigins` 사용.

---

## WebClientConfig — 두 종류 빈

### `aiServiceWebClient` — 집계 응답
- `responseTimeout` / `ReadTimeout` / `WriteTimeout` = **300s**
- `maxInMemorySize` = **10MB** (FastAPI 상태 응답에 마크다운 전문 포함될 수 있음)

### `aiServiceStreamingWebClient` — NDJSON/SSE 스트리밍
- **HTTP/1.1 강제** (`HttpProtocol.HTTP11`) — HTTP/2 멀티플렉싱은 프레임 버퍼링 유발
- `responseTimeout` / `ReadTimeout` = **600s** (긴 스트림)
- `maxInMemorySize` = **256KB** — 라인 단위 처리 가정. 큰 버퍼는 라인을 모아 한 번에 방출
- `WriteTimeout` 30s — 요청 본문은 작으니 짧게

### 공통 ConnectionProvider (`buildBaseHttpClient`)
- `maxConnections` = 50
- `maxIdleTime` = 30s
- `maxLifeTime` = 5m
- `pendingAcquireTimeout` = 30s
- `evictInBackground` = 60s
- `CONNECT_TIMEOUT` = 5s

> 스트리밍 호출은 반드시 `aiServiceStreamingWebClient` 사용 — 일반 빈 사용 시 **256KB 미만이라도** read timeout 짧아 끊김 발생.

---

## AsyncConfig — Executor 3개

| 빈 이름 | core / max / queue | 용도 |
|---|---|---|
| `materialGenerationExecutor` | 5 / 10 / 100 | Phase 3-5 자료 생성 (`material.generation`) |
| `examGradingExecutor` | 3 / 5 / 50 | 시험 채점 |
| `taskExecutor` | 2 / 5 / 50 | 일반 비동기 (`exam.generateExamAsync` 등) |

### RejectedExecutionHandler — `loggingCallerRunsPolicy`
큐 포화 시 **caller 스레드에서 직접 실행** + 경고 로그. AbortPolicy(기본값)는 작업을 버려 사용자 요청 유실 → 사용 금지.

### 종료 동작
- `setWaitForTasksToCompleteOnShutdown(true)` — 진행 중 작업 완료 대기
- `setAwaitTerminationSeconds(60)` — 최대 60초

`@EnableAsync` 가 같이 활성화 — `@Async("<beanName>")` 로 사용. 빈 이름 누락 시 기본 `taskExecutor` 사용.

> ⚠️ caller 실행 = HTTP 요청 스레드(Tomcat) 가 점유. async 의도가 무력화되므로 큐 사이즈 모니터링 필수.

---

## WebMvcConfig — 인터셉터 등록 + MVC async

### 인터셉터
- `RateLimitInterceptor` → `/api/**` (단 `/api/health`, `/api/auth/**`, `/api-docs/**`, `/swagger-ui/**` 제외)
- `AuthRateLimitInterceptor` → `/api/auth/login`, `/api/auth/signup`, `/api/auth/refresh`

### MVC Async (SSE 용)
```java
mvcAsyncTaskExecutor: core 8 / max 32 / queue 200
defaultTimeout: 60_000ms
```
SSE Flux 반환 시 Tomcat async dispatch 가 이 executor 사용. **요청당 1 스레드 점유** — 동시 SSE 32개 한도. 트래픽 늘면 max 증가.

---

## RedisConfig — Redis 빈 일괄

### 빈 4개
- `StringRedisTemplate` — 문자열 기반 (대부분의 캐시/블랙리스트/RateLimit)
- `RedisTemplate<String, Object>` — 직렬화 별도 시 사용 (현재는 String serializer 동일 — 미래용)
- `RedissonClient` — 분산 락. `useSingleServer` (단일 노드). 클러스터 시 변경 필요
- `RedisMessageListenerContainer` — Pub/Sub. 패턴 `shared:progress:*` 구독 (Material Generation 진행 SSE 중계)

### `@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true", matchIfMissing = true)`
`app.redis.enabled=false` 로 끌 수 있음. 단 RateLimit/Cache/Blacklist/AsyncTask 가 모두 Redis 의존 — 끄면 동작 불가능 (fail-open 폴백만 동작).

---

## RateLimitInterceptor / AuthRateLimitInterceptor

### `RateLimitInterceptor` (인증 사용자)
- `SecurityContextHolder.getContext().getAuthentication().getName()` 로 이메일 추출 (DB 조회 X)
- `rateLimitService.isAllowedByEmail(email)` 호출
- 익명 사용자(`anonymousUser`)는 통과 (인증 엔드포인트는 별도)
- `/api/health` 항상 통과
- **fail-open** — Redis 장애 시 통과 + 경고 로그

### `AuthRateLimitInterceptor` (미인증 IP 기반)
| 엔드포인트 | 분당 IP 한도 |
|---|---|
| `POST /api/auth/login` | 10 |
| `POST /api/auth/signup` | 5 |
| `POST /api/auth/refresh` | 20 |

`X-Forwarded-For` → `X-Real-IP` → `RemoteAddr` 순으로 IP 추출. ngrok/리버스 프록시 환경 고려.

---

## GlobalExceptionHandler — `@RestControllerAdvice`

표준 응답 형식 (`ErrorResponse`): `{ status, error, code, message, path }`

### 매핑 우선순위
1. `BusinessException` → 자체 ErrorCode 그대로 (가장 일반)
2. `StreamingApiException` → 4000 (AI 통신 통합 코드)
3. `WebClientResponseException` → 504/408 → `AI_SERVER_TIMEOUT`, 그 외 → AI 통신 오류
4. `AgentExecutionException` / `StreamingException` (BusinessException 상속) → 1번 경로로 흡수
5. `MethodArgumentNotValidException` / `HttpMessageNotReadableException` 등 Spring MVC 표준 예외 → 400
6. `NoResourceFoundException` → 404
7. `Throwable` (catch-all) → 500 + `INTERNAL_SERVER_ERROR`

> prod 응답에 스택트레이스/`statusText` 노출 금지 (CLAUDE.md). 메시지는 사전 정의된 ErrorCode 메시지 + 서비스 측에서 던진 사용자 메시지만.

---

## OpenApiConfig

Swagger UI 의 Authorize 버튼에 JWT Bearer 스키마 추가. `/swagger-ui.html`, `/api-docs/**` 공개. prod 에서는 SecurityConfig 의 `permitAll` 경로에서 제외하는 옵션도 검토.

---

## 상시 주의사항 (Gotcha)

### `dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()`
SSE Flux 반환 시 Tomcat 이 2차 dispatch 하는데, 그때 인증 필터가 다시 돌아 401 발생. 이 두 줄로 회피:
```java
.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
```
이걸 빼면 모든 SSE 가 끊김. 절대 제거하지 말 것.

### CORS 와 Spring Security `cors(config)` 매핑
`cors().configurationSource(...)` 가 있어야 `corsConfigurationSource()` 빈이 적용됨. 없으면 Spring Security 가 무시 → preflight 실패.

### AsyncConfig 의 caller 실행 vs MVC async
- `materialGenerationExecutor` 포화 → `@Async` 호출자 (보통 Controller 스레드) 가 직접 실행 → 응답 지연
- `mvcAsyncTaskExecutor` 포화 → SSE 응답 시작 자체가 지연

따로 모니터링 필요. 큐 길이가 자주 max 근처면 풀 사이즈 키울 것.

### `WebClientConfig` 의 두 빈 이름 헷갈림
- `aiServiceWebClient` — 집계
- `aiServiceStreamingWebClient` — 스트리밍

둘 다 `WebClient` 타입이라 잘못 주입하기 쉬움. 필드명 + `@Qualifier` 명시 권장.

### `RedissonClient` 빈은 `useSingleServer`
프로덕션이 Redis 클러스터/Sentinel 로 바뀌면 이 설정 변경 필요. 현재 `useSingleServer` 라 클러스터 환경에서는 락 정확성 보장 X.

### `RedisMessageListenerContainer` 의 `shared:progress:*` 구독
FastAPI(`llm_multi_agent`) 가 이 채널로 진행률 publish → Spring 측 `MaterialGenerationProgressListener` 가 SSE 중계. **채널 이름 변경 시 양쪽 동기화** 필요.

### `OpenApiConfig` 가 prod 에서도 활성
Swagger 가 prod 에 노출됨. 운영 시 `springdoc.api-docs.enabled=false` 또는 SecurityConfig 의 permit 경로에서 제거하는 게 보안적으로 깔끔.

### `GlobalExceptionHandler` 에서 `BusinessException` 우선
catch-all (`Throwable`) 보다 먼저 매핑되어야 5XX 가 비즈니스 에러를 가리지 않음. 핸들러 메서드 순서가 아니라 **타입 특이성** 으로 매칭됨 — Spring 이 자동 정렬.

### prod 환경변수 체크리스트
- `CORS_ALLOWED_ORIGINS` — prod 도메인
- `SPRING_DATA_REDIS_HOST` / `PORT` — Redis 연결
- `AI_SERVICE_BASE_URL` — FastAPI 주소
- `AI_SERVICE_SECRET_KEY` — AI 콜백 검증 키
- `JWT_SECRET` — JWT HMAC 키 (BASE64)
- `JWT_ACCESS_TOKEN_EXPIRATION_TIME`, `JWT_REFRESH_TOKEN_EXPIRATION_TIME` — ms

---

## 주요 파일

### Security
- `SecurityConfig.java` — FilterChain + CORS
- 의존: `security/jwt/JwtAuthenticationFilter`, `security/oauth/*`, `security/exception/*`

### WebClient
- `WebClientConfig.java` — 2종 빈
- 사용: `integration/fastapi/*`, `agent/base/AbstractAgent`

### Async
- `AsyncConfig.java` — 3개 Executor
- `WebMvcConfig.java` — MVC async + 인터셉터 등록

### Redis
- `RedisConfig.java` — 4개 빈
- 사용: `service/{CacheService, RateLimitService, AsyncTaskService, TokenBlacklistService, DistributedLockService}`, `material.generation.listener`

### Agent
- `AgentConfig.java` — 8개 material Agent 빈

### 예외 / 인터셉터
- `GlobalExceptionHandler.java` — `@RestControllerAdvice`
- `RateLimitInterceptor.java`, `AuthRateLimitInterceptor.java`

### API 문서
- `OpenApiConfig.java` — Swagger Bearer 스키마

### 의존
- `common/dto/ErrorResponse.java`, `common/error/CommonErrorCode.java`
- 위에 나열한 도메인/security/agent/service 패키지 빈들
