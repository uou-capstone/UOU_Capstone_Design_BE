# 코드 리뷰 — 버그 / 개선점 발견 (2026-05-09)

> 백엔드 정적 검토 결과. 두 영역으로 분리:
> - **Part A. Spring Boot** (`uou-capstone/`, `feat/v3-springboot`)
> - **Part B. FastAPI / Python** (`ai-service/`)
>
> 각 항목 우선순위: [Critical] > [High] > [Medium] > [Low].

---

# Part A. Spring Boot 영역

## A1. [Critical] `@Transactional` 안에서 `WebClient.block()` — 5곳 확인

### 증상

CLAUDE.md "금지 사항" 의 첫 번째 항목 — `@Transactional` 안에서 `WebClient.block()` — 이 다섯 군데에서 발생한다. FastAPI 호출이 길어질수록 DB 커넥션이 트랜잭션 종료까지 점유되어 HikariCP `connection-timeout(30s)` 임계 도달 시 다른 요청이 대기 / 타임아웃된다.

### 발견 위치

| # | 파일:라인 | 트랜잭션 | 외부 호출 |
|---|---|---|---|
| 1 | `domain/exam/service/ExamGradingService.java:58` | `@Transactional(readOnly=true) gradeExam(...)` | `callBridgeGrade(...)` → `fastApiBridgeClient.gradeResult(...)` (block) |
| 2 | `domain/exam/service/ExamGradingService.java:141` | `@Transactional gradeAndSaveResult(...)` | 위 1번 메서드를 다시 호출 |
| 3 | `domain/exam/service/ExamSubmissionService.java:271` | `@Transactional submitExam(...)` | `examGradingService.gradeAndSaveResult(...)` |
| 4 | `domain/exam/service/DebateService.java:48,105` | `@Transactional startDebate(...) / respondToDebate(...)` | `fastApiSessionClient.callEvent(...)` (block) |
| 5 | `domain/inquiry/InquiryService.java:42` | `@Transactional answerAiQuestion(...)` | `fastApiQaClient.evaluate(...)` (block) |

`integration/fastapi/README.md` 에 "호출 측에서 트랜잭션 경계 분리 필요" 라고 명시되어 있는데, 실제 코드는 분리되지 않았다.

### 수정 방향

각 메서드를 다음 단계로 분리:
1. `@Transactional(readOnly=true)` 로 권한·존재 검증 + 입력 데이터 로드
2. `@Transactional` 밖에서 FastAPI 호출 (block)
3. 결과 적재만 `@Transactional` 메서드로 분리

`DebateService.resolveLectureContent` 안 PDF 텍스트 추출도 트랜잭션 밖으로 함께 이동.

---

## A2. [Critical] `MaterialService.saveUploadedMaterial` — `@Transactional` 미작동

### 증상

```java
public Material uploadFile(Long lectureId, MultipartFile file) throws IOException {
    // ... WebClient.block() (트랜잭션 밖)
    return saveUploadedMaterial(lectureId, lecture, currentUser, ...);  // ← this. 호출
}

@Transactional
protected Material saveUploadedMaterial(Long lectureId, ...) {  // ← protected
    materialRepository.deleteByLecture_IdAndMaterialType(...);
    Material material = Material.builder()...build();
    return materialRepository.save(material);
}
```

두 가지 이유로 `@Transactional` 이 작동하지 않는다:
1. **자기 호출(self-invocation)** — Spring AOP 프록시 우회
2. **`protected` 가시성** — `@Transactional` 의 기본 프록시는 `public` 메서드에만 어드바이스 적용

결과: `delete + save` 가 단일 트랜잭션이 아니라 두 개의 자동 커밋 쓰기로 실행된다. 중간 예외 시 기존 PDF 가 사라지고 새 PDF 는 등록되지 않은 상태가 된다.

### 수정 방향

- `saveUploadedMaterial` 을 별도 `@Service` (예: `MaterialPersistenceService`) 로 분리하고 `public` 으로 선언.
- 혹은 `TransactionTemplate.execute(status -> { ... })` 로 트랜잭션 경계 명시.

---

## A3. [High] CORS 설정 — 하드코딩된 localhost origins (CLAUDE.md 금지)

`config/SecurityConfig.java:110-117` 에서 하드코딩된 origin 6개를 항상 허용. CLAUDE.md "금지 사항" 의 "CORS origin 하드코딩 — `CORS_ALLOWED_ORIGINS` 환경변수 사용" 위반. `setAllowCredentials(true)` 와 결합되면 prod 에서도 사내망 도구가 인증 헤더를 곁들여 호출 가능.

### 수정 방향

프로필별 분기. `application-local.yml` 에 `cors.allowed-origins` 두고, prod 는 `CORS_ALLOWED_ORIGINS` 환경변수만 사용. SecurityConfig 의 하드코딩 리스트는 제거. `setAllowedHeaders` 의 `*` 와 명시 헤더 혼재도 정리.

---

## A4. [High] `GlobalExceptionHandler` / FastAPI 클라이언트 — 예외 메시지 그대로 노출

```java
// GlobalExceptionHandler.java
public ResponseEntity<ErrorResponse> handleStreamingApiException(StreamingApiException ex, ...) {
    return ResponseEntity.status(ex.getStatusCode())
            .body(ErrorResponse.builder().message(ex.getMessage())...);  // 내부 메시지 그대로
}
public ResponseEntity<ErrorResponse> handleAgentExecutionException(AgentExecutionException ex, ...) {
    return ErrorResponse.toResponseEntity(..., ex.getMessage(), ...);     // 내부 메시지 그대로
}
public ResponseEntity<ErrorResponse> handleStreamingException(StreamingException ex, ...) {
    return ErrorResponse.toResponseEntity(..., ex.getMessage(), ...);     // 내부 메시지 그대로
}
```

추가로 FastAPI 클라이언트들이 `BusinessException` 메시지에 `e.getMessage()` 결합:
- `FastApiBridgeClient.testGenGenerate / quizResult / gradeResult`
- `FastApiSessionClient.getOrCreateByLecture / callEvent`

`BusinessException` 의 메시지는 `ErrorResponse` 를 거쳐 응답 body 에 들어가므로 FastAPI stack trace, 내부 URL, request ID 등이 클라이언트로 전달된다. CLAUDE.md "금지 사항" 의 "내부 정보 노출" 위반.

### 수정 방향

- 위 세 핸들러는 `ex.getMessage()` 대신 ErrorCode 의 고정 메시지 사용. 디버깅 메시지는 `log.error(...)` 에만.
- FastAPI 클라이언트들의 `onErrorMap` 에서도 `e.getMessage()` 제거.

---

## A5. [High] `JwtTokenProvider.getAuthentication` — `ROLE_` 접두사 누락

```java
Collection<? extends GrantedAuthority> authorities =
        Arrays.stream(claims.get("role").toString().split(","))
                .map(SimpleGrantedAuthority::new)   // ← "STUDENT" 그대로
                .collect(Collectors.toList());
```

`hasRole("STUDENT")` 는 내부적으로 `ROLE_STUDENT` 를 비교 → **항상 false**. `@PreAuthorize("hasRole('TEACHER')")` 같은 메서드 보안 미작동. 현재는 `hasAuthority("STUDENT")` 만 동작 — 사용 시 명시적 일관성 필요.

### 수정 방향

발급 시 `"ROLE_" + role` prefix 부착 또는 권한 검사 시 모두 `hasAuthority(...)` 통일. 기존 발급 토큰 호환을 위해 두 방식 동시 매핑 후 점진 전환.

---

## A6. [Medium] `NoticeService.createNotice` — N명 학생에 대해 N번 단건 INSERT (트랜잭션 동기)

`@Transactional` 안에서 ACTIVE 학생 1명당 `notificationService.notify(...)` 단건 호출 → `notificationRepository.save(...)` 단건 INSERT. 1000명 강의 시 INSERT 1000번이 한 트랜잭션 안에서 직렬 실행.

### 수정 방향

- `notificationRepository.saveAll(...)` 또는 JDBC batch INSERT.
- 발송을 `ApplicationEventPublisher.publishEvent(...)` 로 분리 + `@TransactionalEventListener(phase=AFTER_COMMIT)` + `@Async("taskExecutor")` 로 비동기.

---

## A7. [Medium] Rate Limit — 미인증 엔드포인트 무제한

```java
// RateLimitInterceptor.java:34-50
if (authentication != null && authentication.isAuthenticated()
        && !authentication.getName().equals("anonymousUser")) {
    if (!rateLimitService.isAllowedByEmail(userEmail)) { ... }
}
return true;  // 미인증이면 그대로 통과
```

`/api/auth/oauth/exchange`, `/api/ai/callback/**`, `/login/**` 등이 무제한 호출 가능. OAuth exchange code brute force / callback secret guessing 시 시도 누적.

### 수정 방향

미인증일 때 IP 기반 fallback. `/api/auth/**` 는 분당 N회로 별도 limit.

---

## A8. [Medium] `WebClientConfig.aiServiceWebClient` — 호출별 timeout override 부재

`aiServiceWebClient` 의 read/write timeout 이 모두 300초 고정. QA evaluate / status polling 등 짧은 호출도 동일한 timeout. hang 발생 시 톰캣 워커 5분간 묶임.

### 수정 방향

짧은 호출용 `aiServiceFastWebClient` 빈 별도 분리 (30초). `@CircuitBreaker` (Resilience4j) 도입 검토.

---

## A9. [Medium] PII 로깅

| 파일:라인 | 내용 |
|---|---|
| `OAuth2AuthenticationSuccessHandler.java:62` | `log.warn("... email={}", email)` (WARN 레벨이라 prod 출력) |
| `TokenBlacklistService.java:33,67` | `log.debug("Token added: {}", token.substring(0, 20)...)` |

email 은 user id 로 대체, 토큰은 `jti` 만 노출.

---

## A10. [Low] `SecurityConfig` — `/api/lectures/` permitAll 잔존

```java
.requestMatchers("...", "/api/lectures/").permitAll()
```

실제 매핑된 경로는 인증 잘 되지만, `/api/lectures/` 자체는 dead config. 누군가 해당 경로에 핸들러를 매핑하면 의도치 않은 인증 우회. permitAll 목록에서 삭제.

---

## A11. [Low] `MaterialService` — 사용되지 않는 의존성 / 포맷 정리

`userRepository`, `enrollmentRepository`, `courseRepository` 가 주입만 되고 미사용. 또한 빈 줄 2개씩 삽입되어 가독성 저하 — spotless / google-java-format 적용 권장.

---

# Part B. FastAPI / Python 영역

## B1. [Critical] CORS 설정 오류 — `allow_origins=["*"] + allow_credentials=True`

### 증상

`app/main.py:43-50`

```python
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],                     # ← 모든 출처
    allow_credentials=True,                  # ← 동시에 자격증명 허용
    allow_methods=["GET", "POST", ...],
    allow_headers=["*"],
    expose_headers=["*"],
)
```

브라우저는 `Access-Control-Allow-Origin: *` + `Allow-Credentials: true` 조합을 거부하므로 실제로는 동작하지 않을 가능성이 크지만, 만약 어떤 클라이언트가 우회해 동작한다면 모든 출처가 자격증명을 곁들여 FastAPI API 를 호출 가능. 또한 Spring Boot 가 CORS 를 책임지는 구조에서 FastAPI 가 추가로 `*` 를 여는 것은 **혼란을 가져온다** — FastAPI 는 외부에서 직접 호출 받지 않아야 한다 (Spring Boot 만이 클라이언트).

### 수정 방향

- FastAPI 는 사내망(혹은 docker network) 내부에서만 호출되므로 CORS 미들웨어 자체를 제거하거나, `allow_origins=[<spring-boot-host>]` 로 한정.
- `allow_credentials=True` 는 cross-origin cookie/header 가 실제 필요한지 재검토 후 결정.

---

## B2. [Critical] FastAPI 인바운드 인증 부재 — Spring Boot 외부에서 호출 가능

### 증상

`env.sample` 에 `AI_SECRET_KEY=...` 가 정의되어 있지만 어떤 라우터에서도 검증하지 않는다. Spring Boot → FastAPI 호출이 단순 HTTP (`http://ai-service:8000`) 로 이뤄지며 비밀키 헤더 없음. 만약 FastAPI 컨테이너가 외부에 노출(포트 포워딩 / ALB)되면 누구나 다음 작업을 수행할 수 있다:

- `POST /api/files/upload` 로 임의 PDF/MD/TXT 업로드 (디스크 채우기)
- `POST /api/v3/session/by-lecture/{any_id}` 로 세션 임의 생성/조회
- `DELETE /api/v3/session/{any_id}` 로 세션 삭제
- `GET /api/v3/session/{any_id}/state` 로 세션 상태 노출
- `POST /api/v2/qa/evaluate`, `/api/v3/bridge/grade/result` 등으로 Gemini API 비용 폭탄

### 수정 방향

- FastAPI 에 미들웨어 추가: `request.headers.get("X-AI-SECRET-KEY")` 검증. `MessageDigest.isEqual` 같은 상수시간 비교 (Python 은 `hmac.compare_digest`).
- 컨테이너 네트워크 정책: Docker network 외부 접근 차단 (production 에서 `ai-service` 컨테이너 포트를 호스트로 expose 하지 않기).
- `env.sample` 의 `AI_SECRET_KEY=FUCKING_AWSOME_KEY` → 적절한 placeholder 로 교체.

---

## B3. [Critical] Gemini 동기 호출이 async 엔드포인트에서 이벤트 루프 블로킹 — `report.py`, `exam.py`

### 증상

`app/routers/exam.py:107`, `app/routers/report.py:294,314` 에서 동기 SDK 메서드를 `asyncio.to_thread` 없이 호출:

```python
async def _call_gemini_json(...) -> dict:
    ...
    response = client.models.generate_content(    # ← 동기 메서드, 수십 초 블로킹
        model=model, contents=[prompt], config=...,
    )
```

`GeminiBridgeClient` 는 같은 호출을 `await asyncio.to_thread(self._client.models.generate_content, ...)` 로 잘 감쌌는데, exam/report 는 이 패턴을 따르지 않았다. 결과: Gemini 응답 동안 **이벤트 루프 전체** 가 멈춰 다른 요청 모두 대기. uvicorn 단일 워커 환경에서는 한 명의 채점 요청이 사이트 전체를 멈추게 한다.

### 수정 방향

- `_call_gemini_json` / `_call_gemini_text` 를 `await asyncio.to_thread(client.models.generate_content, ...)` 로 감싸거나
- `client.aio.models.generate_content(...)` (async SDK) 로 교체. note_gen phase3/4 가 이미 이 패턴 사용 중.

또한 두 파일 모두 매 요청마다 `client = genai.Client(api_key=api_key)` 를 새로 생성 — 모듈 레벨 싱글톤으로 추출 필요.

---

## B4. [High] `SessionStore.get_or_create` — 동시성 race / 미인증 IDOR

### 증상

```python
# app/core/session_store.py:61-75
async def get_or_create(self, session_id: int, lecture_id: int) -> SessionState:
    state = await self.get(session_id)
    if state is None:
        ...
        state = SessionState(...)
        await self.set(state)        # ← race: 두 코루틴이 동시에 None 을 보고 둘 다 set
    return state
```

같은 `session_id` 로 두 요청이 동시에 들어오면 둘 다 새 SessionState 를 만들어 마지막 set 이 이긴다. Redis `SET ... NX` 또는 distributed lock 필요.

추가로 `app/routers/session.py` 의 `GET /api/v3/session/by-lecture/{lecture_id}` 와 `DELETE /api/v3/session/{session_id}` 는 **소유자 확인이 없다** — 인증이 없는 FastAPI 에서 임의의 lecture_id / session_id 에 대해 호출 가능. (B2 의 인증 미들웨어가 도입되면 일부 완화.)

### 수정 방향

- `get_or_create` 를 Redis Lua / WATCH+MULTI / `SET NX` 로 원자화.
- DELETE 엔드포인트에 lecture_id 매칭 검증 추가 또는 비활성화.

---

## B5. [High] 파일 업로드 — 동기 I/O 블로킹 + 사이즈 미제한

### 증상

`app/routers/upload.py:14-36`

```python
@router.post("/upload")
async def upload_file(file: UploadFile = File(...)):
    ...
    with dest_path.open("wb") as buffer:
        shutil.copyfileobj(file.file, buffer)   # ← 동기 I/O, 100MB → 수 초 블로킹
    ...
```

문제점:
1. **동기 파일 쓰기** — `async def` 안에서 이벤트 루프를 막는다. `aiofiles` 또는 `await asyncio.to_thread(shutil.copyfileobj, ...)` 사용.
2. **사이즈 제한 없음** — 디스크 채우기 (DoS) 가능. `Content-Length` 검증 또는 스트리밍 카운터.
3. **MIME / 매직바이트 미검사** — 확장자만 검사 (`.pdf`, `.md`, `.txt`). 악성 페이로드를 `.pdf` 로 위장 가능.
4. **에러 메시지 누설** — `raise HTTPException(status_code=500, detail=str(e))` 로 내부 예외 노출.

### 수정 방향

```python
import aiofiles
async with aiofiles.open(dest_path, "wb") as f:
    while chunk := await file.read(1024*1024):
        if total_bytes > MAX_UPLOAD_BYTES: raise HTTPException(413, "...")
        await f.write(chunk)
```

500 응답은 고정 메시지로 대체.

---

## B6. [High] 광범위한 예외 메시지 누설 — `str(exc)` / `str(e)` 직접 응답

### 증상 — 거의 모든 라우터/스트리밍 분기에서 발견

| 파일:라인 | 패턴 |
|---|---|
| `app/routers/upload.py:36,64` | `raise HTTPException(status_code=500, detail=str(e))` |
| `app/routers/note_gen.py:57,71,107,133,135` | `raise HTTPException(status_code=500, detail=f"... {str(e)}")` |
| `app/routers/test_gen.py:217` | `raise HTTPException(status_code=500, detail=f"시험 문제 생성 실패: {str(e)}")` |
| `app/routers/qa.py:41` | `raise HTTPException(status_code=500, detail=f"QA 평가 실패: {str(e)}")` |
| `app/routers/pdf.py:28` | `raise HTTPException(status_code=500, detail=f"PDF 분석 실패: {str(e)}")` |
| `app/routers/bridge.py:158,195` | `raise HTTPException(status_code=500, detail=f"... {exc}")` |
| `app/routers/session.py:124` | `raise HTTPException(status_code=500, detail=result.get("error", "처리 실패"))` |
| `app/routers/lecture.py:83`, `bridge.py:121,228`, `report.py:396,463`, `exam.py:465`, `session.py:155` | NDJSON 스트리밍 error 이벤트에 `message=str(exc)` |
| `app/main.py:44` | validation handler 가 `body_str` 과 `exc.errors()` 그대로 반환 |

Python 예외 메시지에는 파일 경로, 스택 정보, Gemini API 응답 본문 등이 포함될 수 있다. Spring Boot 측 GlobalExceptionHandler 가 `WebClientResponseException` 의 body 를 일부 마스킹하지만, FastAPI → Spring Boot 사이의 raw 메시지가 그대로 전달되는 흐름이 많다.

### 수정 방향

- `app/main.py` 에 글로벌 `Exception` 핸들러 추가 — 내부적으로 `logger.exception(...)` 만 하고 응답은 고정 메시지.
- 각 라우터의 `raise HTTPException(500, ...)` 들을 fixed message + 서버 로그로 분리.
- NDJSON `error` 이벤트도 `code` 필드만 전달하고 `message` 는 사용자용 문구로.

---

## B7. [High] `BackgroundTasks` 로 장기 작업 처리 → 워커 점유

### 증상

`app/routers/note_gen.py:98`

```python
background_tasks.add_task(run_phase3_to_5_task, session_id)
return {"session_id": ..., "status": "accepted", ...}
```

FastAPI `BackgroundTasks` 는 응답 송신 후 같은 워커에서 작업을 `await` 한다 — 즉 작업이 끝날 때까지 해당 요청이 점유한 worker slot 이 풀리지 않는다. Phase 3-5 는 Gemini 호출이 여러 차례 들어가 분 단위로 걸린다 (uvicorn 단일 워커라면 그동안 새 요청 거의 처리 불가).

### 수정 방향

- 별도 워커 풀 (Celery, RQ, arq) 도입.
- 즉시 분리해야 한다면 `asyncio.create_task(...)` 로 fire-and-forget — 단 종료 처리/재시도/관측이 필요하다.
- uvicorn workers 수 ≥ 2 로 운영해 한 작업이 다른 요청을 막지 않게 운영 — 임시 미봉책.

---

## B8. [High] 단일 uvicorn 워커 + 1 워커 가정 코드 다수

### 증상

`Dockerfile`

```Dockerfile
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
```

`--workers` 미지정 → 단일 워커. 그런데 코드 다수가 모듈 레벨 캐시(메모리) 사용:
- `GeminiBridgeClient._file_api_mem_cache` (OrderedDict)
- `GeminiBridgeClient._upload_locks`
- `app/routers/test_gen.py: generator: Optional[LectureTestGenerator] = None` (싱글톤)

워커를 늘리면 캐시 / 잠금이 워커별로 별도 (정합성 깨짐). 또한 `get_generator()` 의 None check + 할당은 thread-safe 하지 않음 (asyncio 환경에서는 race 적지만 발생 가능).

### 수정 방향

- 워커 수 늘리기 전 메모리 캐시 → Redis 만 사용으로 통일.
- `get_generator()` 에 `asyncio.Lock` 또는 module-level eager init.

---

## B9. [Medium] `print(...)` 와 `logger.info(...)` 혼용

### 증상

`app/main.py`, `note_gen_service.py`, `test_gen.py` 등에서 `print(f"[ERROR] ...")` 사용. 다른 파일은 `logging.getLogger(__name__).info(...)`. 결과: 로그 포맷 / 레벨 / 타임스탬프 / 컨테이너 stdout aggregation 일관성 없음. 운영 모니터링/검색 어려움.

### 수정 방향

- `app/__init__.py` 에 logging 설정 (formatter, level) 추가.
- 모든 `print(...)` → `logger.info / debug / error`.
- `traceback.print_exc()` → `logger.exception(...)`.

---

## B10. [Medium] Gemini 클라이언트 매 요청 재생성 / `_HEARTBEAT_INTERVAL` 가 SDK 호출 지연을 차단하지 않음

### 증상

- `app/routers/exam.py:103,113`, `report.py:293,313`, `MainQandAAgent.py:119` 에서 `client = genai.Client(api_key=...)` 를 매 호출마다 생성. Connection pool/HTTP 세션 재사용 못함.
- `GeminiBridgeClient.stream()` 의 `_HEARTBEAT_INTERVAL` 은 `queue.get()` 의 timeout 으로 사용 — Gemini 가 응답 시작 전에는 SDK iterator 가 첫 chunk 를 받기까지 오래 블로킹할 수 있고, 그동안 thread 가 수십 초 멈출 수 있다.

### 수정 방향

- Module-level `genai.Client(...)` 싱글톤.
- Streaming 시 producer thread 의 첫 chunk 까지의 시간이 임계 초과 시 별도 timeout 설정.

---

## B11. [Medium] `pypdf` + `PyPDF2` 동시 의존

### 증상

`requirements.txt`

```
pypdf==5.9.0
PyPDF2==3.0.1
```

PyPDF2 는 deprecated (pypdf 의 fork → pypdf 가 정식 후속). 두 라이브러리가 함께 들어가면 dependency tree 비대 + 보안 패치 누락 가능. 어떤 코드가 어느 라이브러리를 쓰는지 confused state.

### 수정 방향

- 모든 `import PyPDF2` 를 `import pypdf` 로 통일하고 `PyPDF2` 제거.

---

## B12. [Medium] `_load_bytes_cached` / `load_text` 동기 파일 I/O

### 증상

`ai_agent/bridge/GeminiBridgeClient.py:54-61, 412-421`

```python
@lru_cache(maxsize=32)
def _load_bytes_cached(pdf_path: str, _mtime_ns: int) -> types.Part:
    data = pathlib.Path(pdf_path).read_bytes()   # ← 동기 read
    return types.Part.from_bytes(...)
```

15MB 미만 PDF 라도 첫 로드는 디스크 read 가 들어가며, 이는 async 컨텍스트에서 이벤트 루프를 막는다. `load_text` 도 마찬가지. cache hit 시는 빠르지만 cold path 에서 블로킹.

### 수정 방향

- `await asyncio.to_thread(pathlib.Path(pdf_path).read_bytes)` 로 감싸기.

---

## B13. [Medium] `path_validator` — `UPLOADS_ROOT` 가 cwd 의존

### 증상

```python
UPLOADS_ROOT: pathlib.Path = pathlib.Path("uploads").resolve()
```

import 시점의 cwd 기준으로 resolve. uvicorn 을 다른 디렉토리에서 띄우면 의도치 않은 상위 경로가 된다. 또 `app/routers/upload.py:48` 에서 같은 패턴이 라우터 호출 때마다 반복 — 일관성 저하.

### 수정 방향

- 환경변수 `UPLOADS_DIR` (기본 `/app/uploads`) 사용 또는 `__file__` 기준 상대 경로로 고정.
- `app/routers/upload.py` 의 `_UPLOADS_DIR` 와 `path_validator.UPLOADS_ROOT` 를 한 곳에서 정의.

---

## B14. [Low] Dockerfile — 컨테이너 `root` 실행

`USER` 디렉티브 없음 → 컨테이너 root 로 실행. 보안 강화: `RUN useradd -m app && chown -R app /app && USER app` 추가.

---

## B15. [Low] `env.sample` — 비속어 포함

`AI_SECRET_KEY=FUCKING_AWSOME_KEY` 를 `your_shared_secret_here` 등으로 교체. 그리고 `AWSOME` 오타도 함께 수정.

---

## B16. [Low] 레거시 CLI `input()` 코드가 서비스 모듈에 남아 있음

`ai_agent/v2/note_gen/agents/phase1_planning.py:96` — `auto_mode=False` 분기에 `input(">>> ")` 가 있음. API 호출 경로에서는 항상 `auto_mode=True` 로 호출되지만, 실수로 False 가 들어오면 서버 워커가 stdin 대기로 멈춘다. 운영 코드에서는 `if not auto_mode: raise RuntimeError("CLI mode disabled in service")` 로 명시 차단 권장.

---

# 우선순위 제안 (전체)

| 순서 | 영역 | 항목 | 사유 |
|---|---|---|---|
| 1 | Spring | A2. `MaterialService.saveUploadedMaterial` 트랜잭션 미작동 | 데이터 정합성 |
| 2 | FastAPI | B2. 인바운드 인증 부재 | 직접 호출 / 비용 폭탄 / DoS |
| 3 | Spring | A1. `@Transactional` + WebClient.block() 5곳 | 운영 중 DB 커넥션 고갈 |
| 4 | FastAPI | B3. Gemini 동기 호출 → 이벤트 루프 블로킹 | 단일 워커 + 동기 호출로 사이트 전체 멈춤 |
| 5 | FastAPI | B1. CORS `*` + credentials | 보안 |
| 6 | Spring | A3. CORS localhost 하드코딩 | 보안 |
| 7 | Spring | A4. 예외 메시지 누설 | 보안 (정보 노출) |
| 8 | FastAPI | B6. 예외 메시지 누설 | 보안 (정보 노출) |
| 9 | FastAPI | B5. 파일 업로드 동기 I/O + 사이즈 제한 | 성능 + DoS |
| 10 | Spring | A5. JwtTokenProvider role prefix | 인가 우회 위험 |

---

# 참조

- `CLAUDE.md` "금지 사항" 4개 항목 — Spring Boot 의 A1·A3·A4 가 직접 위반.
- `integration/fastapi/README.md` — 트랜잭션 경계 분리 명시 (A1 관련).
- FastAPI `app/main.py` 의 CORS / 인증 부재는 "FastAPI 는 Spring Boot 만 호출" 가정에 의존 — 운영 인프라(network policy)와 함께 검증 필요.
