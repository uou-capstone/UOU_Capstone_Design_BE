# material 도메인

강의 자료(PDF)·AI 생성 문서 관리. 이 도메인 작업 시 참고용 상시 메모.
버그·성능 수정 **이력**은 루트 `uou-capstone/DEV_NOTES.md` 참조.

---

## 개요

- **Material 엔티티**: 강의별 PDF/문서. 주요 필드 — `materialType`, `filePath`, `uploadedBy`, `displayName`, `url`
- **Generation 서브패키지**: AI로 강의 자료를 자동 생성하는 5단계 파이프라인 (별도 구조)
- 역할: `TEACHER`가 업로드·생성, `STUDENT`는 조회만

---

## 주요 플로우

### 업로드/스트리밍 (기본 Material)
- `POST /api/lectures/{lectureId}/materials` (TEACHER) — PDF 업로드, FastAPI 저장 후 DB 기록
- `GET /api/materials/{materialId}/file` (TEACHER/STUDENT) — `StreamingResponseBody` + `DataBufferUtils` 기반 청크 스트리밍
- `DELETE /api/materials/{materialId}` (TEACHER)

### 생성 5-Phase 파이프라인
| Phase | 진행률 | 에이전트 | 용도 |
|---|---|---|---|
| 1 | 20% | PlanningAgent | 키워드 → DraftPlan |
| 2 | 40% | ConfirmAgent | 사용자 피드백 반영 → FinalizedBrief |
| 3 | 60% | Decomposition + Write | 챕터 분해·본문 작성 |
| 4 | 80% | Validation + Review | 검증·리뷰 |
| 5 | 100% | EditorAgent | 최종 문서 조립 |

- 컨트롤러 prefix: `/api/materials/generation`
  - 동기 REST: `POST .../phase{1..5}` (`MaterialGenerationController`)
  - SSE: `GET .../phase{1..5}/stream` (`MaterialGenerationStreamController`) — 실시간 delta 중계
- Phase 1~2: 동기 REST + SSE 병행 제공
- Phase 3~5: `@Async("materialGenerationExecutor")` 로 백그라운드 처리 (`processPhase3To5Async`)
- 진행 상황은 Redis Pub/Sub 채널 `shared:progress:{sessionId}` → `MaterialGenerationProgressListener` 가 활성 SSE Emitter로 중계

---

## 외부 의존

- **FastAPI ai-service** (실 구현은 `llm_multi_agent` 브랜치)
  - 시작: `POST /api/v2/lecture-gen/phase3-5/auto` — `task_id` + `status_url` 반환
  - 폴링: 응답에서 받은 `status_url` 을 그대로 GET (동적 URL — 하드코딩하지 말 것)
  - 클라이언트: `integration/fastapi/FastApiNoteGenClient` (`startPhase3To5Auto`, `getStatusByUrl`)
  - WebClient 베이스 URL: `${ai.service.base-url}` — local `http://localhost:8000`, prod `http://ai-service:8000`
  - 타임아웃: 집계 응답 300s, 스트림 600s, maxInMemorySize 10MB
- **Phase 3~5 폴링**: `Thread.sleep(5000)` × `maxAttempts=240` → **약 20분 타임아웃** + 주기적 진행 로그 (`MaterialGenerationService` 3390·3557라인대).  
  → **주의**: async 스레드풀(core 5/max 10) 점유. 동시 대량 요청 시 풀 포화 가능.
  → 코드에는 구버전(ko 브랜치) 호환용 Redis `fa:result:{sessionId}` 키 폴링 분기가 남아 있음 — 현재 `llm_multi_agent` FastAPI 는 `task_id/status_url` 분기만 사용.

---

## 상시 주의사항 (Gotcha)

### N+1 방지 — `findByIdWithLecture` 고정 사용
`GenerationSession` 조회는 **반드시** `generationSessionRepository.findByIdWithLecture(id)` 사용.
`findById`는 `session.getLecture().getCourse().getTeacher()` 체인에서 3~4회 lazy loading 발생.
최신 세션 조회는 `findByLectureAndUserWithLecture(lectureId, userId)` (List 반환 후 stream().findFirst()).

### PDF path 처리
- `pdfPath` 파라미터 누락 시 `materialRepository.findFirstByLecture_IdAndMaterialTypeOrderByCreatedAtDesc(lectureId, "PDF")` 로 **강의의 최신 PDF 자동 조회**. 없으면 `null` 그대로 FastAPI에 전달 (PDF 없는 세션 처리).
- v2.7 이후 `pdf_path`는 **UUID 기반 상대경로** 가능 (`uploads/<uuid>.pdf`) — 절대 파싱·가공 금지, 그대로 전달.
- `filePath`가 `null`이면 스트리밍 전에 체크 (`MaterialService.streamFile`).

### 트랜잭션 경계
`MaterialService.uploadFile`: WebClient 호출을 `@Transactional` **밖** 에서 먼저 실행, DB 기록만 별도 `@Transactional` 메서드로 분리. 커넥션 장기 점유 방지.
`streamFile`: `readOnly` 트랜잭션 제거 — 스트리밍 중 DB 커넥션 유지되지 않도록.

### 사용자 조회
`MaterialGenerationService` 내부에서 `userRepository.findByEmail` 직접 호출 **금지** → `currentUserResolver.getUser()` 사용.
**@Async 메서드 가이드**: `processPhase3To5Async(taskId, sessionId)` 처럼 **세션 ID 만 받고** `session.getUser()` 로 사용자에 접근. 만약 새 @Async 메서드에서 사용자 정보가 꼭 필요하면 `userRepository.findByEmailWithRoles(email)` 1회 허용 (@RequestScope 접근 불가).

### 거대 서비스 파일
`MaterialGenerationService.java` 약 3,950줄 (4,000줄 임박). 새 기능 추가 시 **해당 Phase 블록 내부**로 한정해 변경. 전역 리팩토링은 별도 티켓 (`DEV_NOTES.md` 3-3 항목).

---

## 주요 파일

### Controller
- `controller/MaterialController.java` — 업로드·스트리밍·삭제
- `generation/controller/MaterialGenerationController.java` — Phase 1·2 REST + status 조회
- `generation/controller/MaterialGenerationStreamController.java` — Phase 1~5 SSE

### Service
- `service/MaterialService.java` — 기본 업로드/스트리밍 (트랜잭션·HTTP 분리 완료)
- `generation/service/MaterialGenerationService.java` — 5-Phase 핵심. 진입점: `startPhase1`, `processPhase2`, `processPhase3`, `processPhase4`, `processPhase5`, `processPhase3To5Async`
- `generation/service/MaterialGenerationStreamService.java` — Phase별 SSE 중계

### Listener (Redis Pub/Sub → SSE)
- `generation/listener/MaterialGenerationProgressListener.java` — `shared:progress:{sessionId}` 채널 구독, 활성 `SseEmitter` 에 진행률 전달

### Repository
- `generation/GenerationSessionRepository.java` — `findByIdWithLecture`, `findByLectureAndUserWithLecture` (JOIN FETCH 필수)
- `repository/MaterialRepository.java` — `findByLecture_IdOrderByCreatedAtDesc`, `findByLecture_IdInOrderByLecture_IdAscCreatedAtDesc` (벌크), `findFirstByLecture_IdAndMaterialTypeOrderByCreatedAtDesc`, `deleteByLecture_IdAndMaterialType` (PDF 1개 정책)

### Entity
- `entity/Material.java` — `lecture`, `displayName`, `materialType`, `filePath`, `url`, `uploadedBy`
- `generation/GenerationSession.java` — 5-Phase 산출물(JSON 컬럼) + 진행률·에러 메시지

### 외부 통신
- `integration/fastapi/FastApiNoteGenClient.java` — Phase 3~5 자동 시작 + 상태 폴링 (FastAPI 호출 단일 진입점)

### 외부 설정
- `config/WebClientConfig.java` — ai-service URL·타임아웃
- `config/AsyncConfig.java` — `materialGenerationExecutor` 빈 정의 (core 5 / max 10 / queue 100)
