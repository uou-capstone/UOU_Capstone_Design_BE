# exam 도메인

5종 시험(`ExamType`) 생성·응시·채점·토론 흐름. 이 도메인 작업 시 참고용 상시 메모.
버그·성능 수정 **이력** 은 루트 `uou-capstone/DEV_NOTES.md` 참조.

---

## 개요

- **시험 유형 (`ExamType`)**: `FLASH_CARD` / `OX_PROBLEM` / `FIVE_CHOICE` / `SHORT_ANSWER` / `DEBATE`
- **세션 상태 (`ExamStatus`)**: `GENERATING` → `READY` → `COMPLETED` / `FAILED`
- 역할:
  - **TEACHER**: 시험 생성·세션 조회·삭제·복구·스트리밍 시청
  - **STUDENT/TEACHER**: 응시·채점, 토론

핵심 엔티티
- `ExamSession` — 단일 시험 카드. `priorProfileJson`(TestProfile), `examContentJson`(문제 리스트), `evaluationLogJson`(채점 결과) JSON 컬럼으로 한 행에 모든 산출물
- `ExamProfile` — `(user, lecture)` 유니크 사용자별 프로필 + `contentHash` (강의 내용 MD5 — Redis 캐시 키와 동일)
- `ExamQuestion` — `assessment` 필수 + `examSession` nullable. `assessment` 도메인과 연결되는 정형 문제 행
- `ExamResult` — 응시 결과. `totalScore`/`maxScore` (DECIMAL 5,2), `overallFeedback`, `userFeedbackJson`

---

## 주요 플로우

### 1. 시험 생성 (`ExamGenerationController`)
prefix: `/api/exams/generation`

| 메서드/경로 | 권한 | 용도 |
|---|---|---|
| `POST .` | TEACHER | 동기 생성 (FLASH_CARD / OX_PROBLEM 만 안정 — 나머지는 진행 중) |
| `POST ./async` | TEACHER | 비동기 생성 — `taskId` 즉시 반환, `/api/tasks/{taskId}/status` 폴링 |
| `GET ./{examSessionId}` | TEACHER | 세션 + 사용된 Profile + 문제 리스트 조회 |
| `DELETE ./{examSessionId}` | TEACHER | 세션 단건 삭제 (해당 강의 소유자만) |
| `POST ./{examSessionId}/recover` | TEACHER | `FAILED` → `GENERATING` 으로 되돌려 재시도 가능하게 |
| `GET ./stream?examSessionId=` | TEACHER | Agent 추론 과정 SSE (`event: message` thought/answer delta) |

비동기 처리 핵심
```java
@Async("taskExecutor")
@Transactional
public void generateExamAsync(String taskId, ExamGenerationRequestDto requestDto, String userEmail) { ... }
```
- 컨트롤러에서 `SecurityContextHolder.getContext().getAuthentication().getName()` 으로 **userEmail 추출 후 인자로 전달** — `@Async` 스레드에 SecurityContext 가 자동 전파되지 않음
- 진행 상태는 `AsyncTaskService.updateTaskStatus(taskId, status, progress, message [, resultJson])` 로 갱신

### 2. 시험 응시·채점 (`ExamSubmissionController`)
prefix: `/api/exams/submission`

| 메서드/경로 | 권한 | 용도 |
|---|---|---|
| `POST .` | STUDENT/TEACHER | 답변 제출 → `ExamGraderAgent` 채점 → `ExamResult` 저장 |
| `GET ./{examResultId}` | STUDENT/TEACHER | 채점 결과 + 문제별 피드백 조회 |

응답 본문은 `gradingDetails.questionGradings[].{userAnswer, isCorrect, score, feedback}` 구조.

### 3. 토론형 시험 (`DebateController`)
prefix: `/api/exams/debate`. `DebatePhase` (PHASE1 / PHASE2 / PHASE3) 로 상태 진행.

| 메서드/경로 | 용도 |
|---|---|
| `POST ./start` | Phase 1 — 모드(debate/socratic/feynman) + 주제 선정 |
| `POST ./respond` | Phase 2 — 사용자 입력 → AI 반박 + 평가 (경쟁 루프) |

### 4. 시험 생성 스트리밍 (`ExamGenerationStreamController`)
- FastAPI `/stream` 응답을 `StreamingEvent { type, delta, content }` 로 변환해 SSE (`event=message` / `event=error`) 발행
- 시험 유형별 `GeneratorAgent.executeStreaming()` 분기

---

## 외부 의존

| 클라이언트 | 위치 | 용도 |
|---|---|---|
| `FastApiBridgeClient` | `integration/fastapi/` | `POST /api/v2/test-gen/generate` (단건 시험 생성) |
| `FastApiSessionClient` | `integration/fastapi/` | 토론 세션 이벤트 (`callEvent(sessionId, eventBody)`) |

- FastAPI 시험 ID 매핑: `DebateService.toFastApiSessionId(examSessionId)` 로 우리 세션 ID → FastAPI 세션 ID 변환
- snake_case ↔ camelCase 변환은 `ObjectMapper.copy().setPropertyNamingStrategy(SNAKE_CASE)` 로 호출 직전 1회만 (전역 매퍼 변경 금지)

---

## 상시 주의사항 (Gotcha)

### `@Async` 메서드의 SecurityContext
`@Async("taskExecutor")` 메서드는 `currentUserResolver.getUser()` 호출 **불가** (`@RequestScope` 비전파). 컨트롤러에서 `userEmail` 등을 추출해 인자로 전달하거나, 내부에서 `userRepository.findByEmailWithRoles(email)` 1회 사용.

### ExamSession 의 JSON 컬럼 갱신은 도메인 메서드로
직접 setter 가 없고 `updatePriorProfile`, `updateExamContent`(→ `READY`), `updateEvaluationLog`(→ `COMPLETED`), `markAsFailed`, `updateDebatePhase`, `updateDebateHistory` 만 노출.
**`updateExamContent` 호출 시 자동으로 status 가 `READY` 로 바뀌므로** 부분 업데이트로 사용하면 안 됨.

### ExamProfile 캐시 키
`contentHash` 는 강의 내용의 MD5. 동일 contentHash 면 Redis/DB 양쪽에서 재사용. 강의 자료가 갱신되면 hash 가 달라져 자연 무효화 — 명시적 invalidate 불필요.

### 강의 삭제 시 cascade
`Lecture` 삭제 흐름은 `course/lecture` 도메인이 주도. exam 도메인 측은:
- `ExamProfileRepository.deleteByLectureId(lectureId)`
- `ExamSessionRepository.deleteByLectureId(lectureId)`
를 강의 삭제 직전에 호출 (FK 제약 방지). 순서 가이드는 `course/lecture/README.md` 참조.

### v1 호환 필드
- `ExamQuestion.assessment` 는 `nullable=false` 지만 `examSession` 은 nullable — v1 `Assessment` 흐름이 메인이고 v2 `ExamSession` 은 추가 참조
- `ExamResult.submission` 도 nullable (v1 호환)

신규 코드는 **v2 (`ExamSession` 기반)** 만 사용. v1 `Assessment`/`Submission` 직접 호출 금지.

### SHORT_ANSWER / FIVE_CHOICE / DEBATE 생성 안정도
컨트롤러 description 에 "현재 FLASH_CARD와 OX_PROBLEM을 지원하며, 나머지 유형은 개발 중" 명시. 실제 코드는 5종 모두 분기되어 있으나 FastAPI 측 안정도가 다름 — 신규 사용 전 FastAPI(`llm_multi_agent`) 측 generator 동작 확인 필요.

### 거대 서비스 파일
- `ExamSubmissionService.java` 794줄
- `ExamGenerationService.java` 623줄

새 채점 로직 / 새 시험 유형 추가 시 **해당 시험 유형 분기 안** 으로 한정. 전역 리팩토링은 별도 티켓.

---

## 주요 파일

### Controller
- `controller/ExamGenerationController.java` — 생성 동기/비동기 + 조회·삭제·복구
- `controller/ExamGenerationStreamController.java` — Agent SSE
- `controller/ExamSubmissionController.java` — 응시·채점·결과 조회
- `controller/DebateController.java` — 토론 Phase 1/2

### Service
- `service/ExamGenerationService.java` — 5종 분기 + Profile 처리 + `generateExamAsync`
- `service/ExamGenerationStreamService.java` — Agent.executeStreaming() 디스패치
- `service/ExamSubmissionService.java` — 답변 검증·채점 호출
- `service/ExamGradingService.java` — 채점 로직 (Agent 호출)
- `service/DebateService.java` — Phase 진행 + FastAPI 세션 이벤트 중계

### Repository
- `repository/ExamSessionRepository.java` — `findByLecture_IdIn` (벌크), `findByLectureIdAndExamType`, `deleteByLecture(Course)?Id`
- `repository/ExamProfileRepository.java` — `findByUserAndLecture`, `findByContentHash`
- `repository/ExamQuestionRepository.java` — `findByExamSessionIdOrderByQuestionOrder`
- `repository/ExamResultRepository.java` — `findByExamSession`

### Entity
- `entity/ExamSession.java` — 5종 시험 단일 행 (JSON 컬럼 3개 + 토론 전용 2개)
- `entity/ExamProfile.java` — `(user, lecture)` 유니크 + `contentHash`
- `entity/ExamQuestion.java` — `assessment` 필수 (v1 호환), `examSession` 선택
- `entity/ExamResult.java` — 점수·총평·사용자 피드백 프로필
- `entity/ExamType.java`, `entity/ExamStatus.java`, `entity/DebatePhase.java`

### DTO (요청/응답)
- 생성: `ExamGenerationRequestDto`, `ExamGenerationResponseDto`, `FastApiExamRequestDto`
- 응시: `ExamSubmissionRequestDto`, `AnswerSubmissionDto`, `ExamSubmissionResponseDto`, `GradingResponseDto`, `QuestionGradingDto`
- 토론: `DebateStart{Request,Response}Dto`, `DebateRespond{Request,Response}Dto`, `DebateTopicDto`, `EvaluationItemDto`
- 시험 유형: `FlashCardDto`, `OxProblemDto`, `FiveChoiceProblemDto`/`FiveChoiceOptionDto`, `ShortAnswerProblemDto`
- 프로필: `TestProfileDto`, `LearningGoalDto`, `UserStatusDto`, `InteractionStyleDto`, `FeedbackPreferenceDto`, `ScopeBoundary`, `UserFeedbackProfileDto`, `ProfileConversationRequestDto`/`ProfileConversationResponseDto`
- 메타: `SessionMetaDto`

### 외부 통신
- `integration/fastapi/FastApiBridgeClient.java` — `POST /api/v2/test-gen/generate`
- `integration/fastapi/FastApiSessionClient.java` — 토론 세션 이벤트
