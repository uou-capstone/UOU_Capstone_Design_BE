# 개발 노트 — 문제 발견 & 수정 이력

> 발견한 버그·성능 문제와 그 원인 추적 과정, 수정 방법을 기록한다.

---

## [2026-05-12] 교사 알림 확장 — 학생 이벤트 8종 + 자기 작업 토글

### 배경

알림 인프라(`Notification`/`NotificationService`/SSE)는 학생/교사 공통이지만, 발행 지점은 **학생을 향한 8종**(`COURSE_JOIN_*`, `COURSE_MEMBER_*`, `NOTICE_*`, `DISCUSSION_COMMENT_RECEIVED`)뿐이었다. 교사 입장에서 자신의 강의실에 일어난 일(가입 요청·새 토론글·과제·시험 제출·AI 생성 결과)을 알 수 없어 매번 페이지를 들어가 확인해야 했다.

### 변경 — 핵심

#### `NotificationType` enum 9종 추가

학생/공통 8종은 그대로. 교사용 신규:
`COURSE_JOIN_REQUESTED`, `DISCUSSION_CREATED`, `DISCUSSION_COMMENTED`, `NOTICE_COMMENTED`, `ASSESSMENT_SUBMITTED`, `EXAM_SUBMITTED`, `AI_GENERATION_COMPLETED`, `AI_GENERATION_FAILED`, `TEACHER_ACTION_CONFIRMED`.

#### `TeacherNotificationPreference` 엔티티 + 토글 1개

`teacher_notification_preferences` 테이블 신설 (Teacher OneToOne, `include_self_action_notifications` bit, 기본 `false`).
- `false`: 학생/시스템 이벤트만 수신.
- `true`: 본인 작업 시 추가로 `TEACHER_ACTION_CONFIRMED` 알림 수신.
- 조회 시 row 없으면 lazy-create.

#### `TeacherNotificationPublisher` 단일 경유 헬퍼

도메인 서비스가 직접 `NotificationService` 를 호출하던 패턴 대신:
- `notifyCourseTeacher(course, actor, …)` — actor 가 교사 본인이면 `TEACHER_ACTION_CONFIRMED` 로 자동 변환 후 설정 ON 시에만 발행. 아니면 일반 알림 그대로.
- `notifySelfAction(teacher, …)` — 설정 ON 시에만 발행.

이 단일 지점에서 자기 작업 분기를 처리해 도메인 코드는 "담당 교사에게 알리고 싶다" 만 신경쓰면 됨.

#### REST API 2종

`NotificationController` 에 추가, 둘 다 `@PreAuthorize("hasAuthority('TEACHER')")`:
- `GET  /api/notifications/teacher-preferences`
- `PATCH /api/notifications/teacher-preferences`

응답/요청 DTO: `{ "includeSelfActionNotifications": boolean }`.

#### 자동 발행 지점 7개

| 도메인 | 메서드 | 발행 |
|---|---|---|
| `CourseJoinRequestService.persistPendingJoinRequest` | save 직후 | `COURSE_JOIN_REQUESTED` |
| `DiscussionService.createDiscussion` | save 직후 | `DISCUSSION_CREATED` (작성자가 교사면 자동 분기) |
| `DiscussionCommentService.createComment` | save 직후 | `DISCUSSION_COMMENTED` (parent 작성자가 곧 담당 교사면 중복 방지) |
| `NoticeCommentService.createComment` | save 직후 | `NOTICE_COMMENTED` (parent 작성자가 곧 담당 교사면 중복 방지) |
| `SubmissionService.createSubmission` | save 직후 | `ASSESSMENT_SUBMITTED` |
| `ExamSubmissionService.submitExam` | 채점 결과 저장 직후 | `EXAM_SUBMITTED` |
| `MaterialGenerationService.processPhase3To5Async` | try 완료 / catch 실패 | `AI_GENERATION_COMPLETED` / `AI_GENERATION_FAILED` (resourceType=`material`) |
| `ExamGenerationService.generateExamAsync` | try 완료 / catch 실패 | `AI_GENERATION_COMPLETED` / `AI_GENERATION_FAILED` (resourceType=`exam`) |

AI 생성 알림은 actor=null 로 호출 — 요청자가 곧 담당 교사라도 비동기 결과 통지는 항상 받아야 하므로 자기 작업 분기 미발동.

### Flyway V4 — `notifications.type` 컬럼 정렬

`V4__teacher_notification_prefs_and_type_widen.sql`:
1. `ALTER TABLE notifications MODIFY COLUMN type VARCHAR(40)` — V1 baseline 의 `ENUM(...)` 을 VARCHAR로 정렬. NotificationType.java 의 주석/설계 의도(`@Enumerated(STRING)` + varchar(40))와 일치. 이후 enum 값 추가 시 DDL 변경 영원히 불필요.
2. `teacher_notification_preferences` 테이블 생성.

### 회귀 보호

- 기존 학생 알림 8종 발행 코드는 그대로 유지 → 기존 학생 흐름 무영향.
- 기존 테스트 4종 (`CourseJoinRequestServiceTest`, `Discussion[Comment]ServiceTest`, `NoticeCommentServiceTest`) 에 `@Mock TeacherNotificationPublisher` 1줄 추가 — 동작 검증은 그대로.
- 신규 테스트 2종: `TeacherNotificationPreferenceServiceTest` (lazy-create / TEACHER 권한 차단 / `isSelfActionNotificationsEnabled`), `TeacherNotificationPublisherTest` (actor 분기 / opt-in/out / null 안전성).
- `./gradlew test --no-daemon` 전체 통과 (Spring 컨텍스트 로드 + V4 마이그레이션 적용 검증 포함).

### FE 인계

`docs/handoff/TEACHER_NOTIFICATION_FE.md` — 새 alert 타입 라우팅 매핑 + 설정 API 사용 예시 + 검증 시나리오.

### Open Items

- `TEACHER_ACTION_CONFIRMED` 의 명시 발행 지점(강의실/강의/공지/자료/평가 본인 CRUD 완료) 은 초기엔 좁게 시작 — 사용자 피드백 후 확장 검토.
- 멀티 인스턴스 환경에서 SSE 분배는 여전히 in-memory `NotificationStreamRegistry` 단일 노드 한정. Redis Pub/Sub 분배는 별도 라운드 (학생 알림과 동일 제약).

---

## [2026-05-11] FastAPI `/bridge/*` 인증 + MergeEdu 신규 4종 도메인

### 배경

FastAPI 측에서 `feat/refactor` 브랜치에 신규 MergeEdu Agent `/bridge/*` 계약을 확정 (`ai-service/docs/BRIDGE_AGENT_ENDPOINTS.md`, `ai-service/docs/SPRING_BRIDGE_AUTH_INTEGRATION.md`). 운영/공유 환경부터 모든 `/bridge/*` 요청에 `X-AI-SECRET-KEY` 헤더가 요구된다. Spring 측 WebClient에는 헤더 주입 코드가 전혀 없는 상태였다.

MERGEEDU 5종 모두 신설 (초기엔 4종 + 후속에 5번째 추가):
1. Discussion AI Assistant
2. Exam Studio (PDF Context + Chat)
3. Report Criteria CRUD + AI Assistant
4. Classroom Report (sync + stream)
5. Student Report Chatbot — FastAPI 팀 인계 항목으로 미루었다가 같은 라운드에 추가.

### 인프라 변경

#### `WebClientConfig` — secret-key 전역 헤더 + prod 시작 실패 가드

`aiServiceWebClient` / `aiServiceStreamingWebClient` 양쪽에 `defaultHeader("X-AI-SECRET-KEY", ...)` 적용. `@PostConstruct validateAiSecretKey()` 추가:
- 활성 프로필에 `prod` / `production` 포함 시 secret이 blank/placeholder 면 `IllegalStateException` 으로 부팅 실패.
- placeholder 목록: `YOUR_SUPER_SECRET_AI_KEY_12345`, `YOUR_AI_SECRET_KEY`, `CHANGE_ME`, `changeme`, `placeholder`.
- local/test 는 blank/placeholder 도 경고 로그만.

환경변수 명명: Spring 측은 기존 `AI_SERVICE_SECRET_KEY` 유지 (기존 yml 정합). FastAPI 측 `AI_SECRET_KEY` 와 **값은 같지만 변수명은 다름** — docker-compose / k8s 매핑에서 같은 값을 두 변수에 주입 필요.

#### `application.yml` — 공통 ai.service.* 기본 선언

`AI_SERVICE_BASE_URL` / `AI_SERVICE_SECRET_KEY` 환경변수의 기본 선언(공백). 프로필별 yml은 그대로.

### `FastApiBridgeClient` — 신규 6 메서드 추가

기존 4 메서드(`testGenGenerate`, `quizResult`, `gradeResult`, `streamQuiz`)는 그대로 유지. 신규는 모두 `/bridge/*` prefix (기존 `/api/v3/bridge/*` 와 별개 계약).

| 메서드 | Path | WebClient |
|---|---|---|
| `discussionAssistantStream` | `POST /bridge/discussion_assistant_stream` | streaming |
| `examStudioPdfContext` | `POST /bridge/exam_studio/pdf_context` | json |
| `examStudioChatStream` | `POST /bridge/exam_studio/chat_stream` | streaming |
| `reportCriteriaAssistantStream` | `POST /bridge/report/criteria_assistant_stream` | streaming |
| `reportClassroomAnalyze` | `POST /bridge/report/classroom_analyze` | json |
| `reportClassroomAnalyzeStream` | `POST /bridge/report/classroom_analyze_stream` | streaming |

스트리밍 메서드는 `aiServiceStreamingWebClient` (HTTP/1.1 강제 + 256KB 버퍼) 주입. 기존 `streamQuiz`는 여전히 `aiServiceWebClient` 사용 — 후속 정리 후보 (Open Items).

### `SseStreamSupport.wrapNdjsonByType` — NDJSON type → SSE event name 매핑

기존 `wrapNdjson`은 모든 라인을 `event: message` 단일 이름으로 래핑. 신규 4종은 클라이언트가 `thought_delta` / `answer_delta` / `criterion_suggestion` / `done` / `error` 를 분기해야 하므로 신규 헬퍼 추가:
- NDJSON line의 `type` 필드를 SSE event name으로 매핑.
- 파싱 실패 라인은 `event: message` + `{"raw": "..."}` fallback.
- **`error` 라인의 `details.errorType` 은 서버 로그에만 기록하고 SSE forward에서 제거** — CLAUDE.md "prod 에러 응답에 내부 정보 노출 금지" 준수.
- FastAPI 가 자체 `done` 라인을 emit 하므로 호출자는 `SseStreamPolicy.appendDoneOnComplete(false)` 권장.

### 도메인 4종 신규

#### 1) `domain/course/discussion/assistant/`

- `POST /api/courses/{cid}/discussions/assistant/stream` (SSE) — 학생/교사
- `DiscussionAssistantService` 가 최근 5개 discussion (`findTop5ByCourseOrderByCreatedAtDesc`) + topic/category/previousDraft 컨텍스트 빌드 → FastAPI 호출 → SSE forward.
- 권한: `CourseAccessService.loadCourseAsParticipant`.

#### 2) `domain/exam/studio/`

- `POST /api/courses/{cid}/exam-studio/pdf-context` (JSON) — Material 의 PDF 를 FastAPI 에 등록, contextId 발급.
- `POST /api/courses/{cid}/exam-studio/chat/stream` (SSE) — contextId 와 메시지로 AI 대화.
- `MaterialRepository.findByIdWithLectureAndCourse` 신규 (JOIN FETCH) — material → lecture → course 권한 체크 한 쿼리.
- 권한: 교사 (`loadCourseAsTeacher`).
- **MVP 한계**: `contextId` 만료(`PROCESS_MEMORY` cache TTL/재시작/multi-worker 라우팅) 시 자동 재발급 retry 미구현 — FE 측 contextId 재발급 흐름으로 위임 (TODO).

#### 3) `domain/course/report/criteria/`

- CRUD: GET/POST/PATCH/DELETE `/api/courses/{cid}/reports/criteria[/{id}]`
- AI 추천: `POST .../criteria/assistant/stream` (SSE) — `criterion_suggestion` 중간 이벤트 multi-emit.
- `CourseReportCriterion` 엔티티 신규 (`course_report_criteria` 테이블, V3).
- `weight` 범위 0–100 (FastAPI 미정 → 우리 측 결정).
- `language` 기본 `"ko"`, `desiredCount` 기본 3.
- 권한: 교사.

#### 5) `domain/course/report/studentchat/` *(후속 추가)*

- `POST /api/courses/{cid}/reports/students/{sid}/chat/stream` (SSE) — 교사가 학생 리포트로 follow-up 질문.
- `StudentReportChatController` 메서드는 `CourseReportController`에 추가.
- `StudentReportChatService` 가 기존 `CourseStudentReportService.getStudentAiReportContext` + `getStudentReportDetail` 호출 결과를 묶어 FastAPI `/bridge/report/student_chat_stream` 으로 forward (대화 이력 미저장 — FE 가 매 요청 `messages[]` 전달).
- `FastApiBridgeClient.studentReportChatStream` 신규.
- Request DTO `@AssertTrue` 로 `question` 또는 `messages` 중 하나 필수 검증.
- 권한: 교사 (`CourseStudentReportService` 내부 검증).

#### 4) `domain/course/report/classroom/`

- `GET /api/courses/{cid}/reports/classroom` — 저장된 분석 결과 1행 조회 (미생성 시 204).
- `POST .../classroom/analyze` (JSON) / `POST .../classroom/analyze/stream` (SSE) — FastAPI 분석 호출 후 UPSERT.
- `ClassroomReport` 엔티티 신규 (`classroom_reports` 테이블, course_id UNIQUE, V3).
- `ClassroomReportPersister` 별도 빈으로 분리 — `@Transactional` self-call 회피.
- 스트림 경로: `doOnNext` 에서 `event: done` 수신 시 `data.data` 추출하여 UPSERT.
- 권한: 교사.
- **MVP 한계**: 학생 데이터는 `getStudentReportList(... PageRequest.of(0, 1000))` 로 collect — 1000명 이상 강의실은 일부만 포함, 경고 로그만. 페이지 분할은 후속.

### Flyway V3 마이그레이션

`src/main/resources/db/migration/V3__report_criteria_and_classroom.sql` — 2개 테이블 일괄:
- `course_report_criteria` — `criterion_id`, `course_id` (FK ON DELETE CASCADE), `label`/`description`/`weight`, BaseTime 컬럼.
- `classroom_reports` — `classroom_report_id`, `course_id` (FK + UNIQUE), `summary_markdown`/`highlights_json`/`risks_json`/`coaching_priorities_json` (LONGTEXT JSON), `source`/`fallback_used`/`fallback_reason`/`confidence`, `generated_at`.

### CLAUDE.md 금지 패턴 준수 확인

- `userRepository.findByEmail(` 직접 호출 — 신규 코드 없음 ✅
- `RuntimeException` — 신규 코드 없음, 모두 `BusinessException(CommonErrorCode.AI_SERVER_ERROR)` ✅
- `@Transactional` + `WebClient.block()` — 모든 FastAPI 호출 메서드는 `@Transactional` **없음**. `ClassroomReportPersister.upsert(@Transactional)` 는 DB UPSERT 만 (block 호출 없음) ✅
- prod SSE 응답에 `errorType` / stack trace 노출 — `wrapNdjsonByType` 가 `details.errorType` 자동 strip ✅
- CORS origin 하드코딩 — 변경 없음 ✅

### FastAPI 팀 인계 사항

별도 메모로 전달할 항목 (사용자 요청):

1. **~~`/bridge/report/student_chat_stream` (Student Report Chatbot)~~** — *후속 추가로 같은 라운드에 구현 완료.* `StudentReportChatService` 가 context + report DTO 묶어 FastAPI 에 forward. 대화 이력 미저장.
2. **Spring 가정값** (FastAPI 측 확정 필요):
   - Discussion 컨텍스트 이전 게시글 — Spring 5개 가정.
   - Criteria `weight` 0–100, `desiredCount` 3, `language` `"ko"`.
   - Classroom 캐싱: 1 course = 1 row UPSERT, 새 학생 추가 시 invalidate 없음 (수동 재생성).
   - 학생 100명+ 시 배치 분할 미구현 (현재 1000개 한계).
3. **NDJSON `details.errorType` 처리**: Spring 은 내부 로그용으로만 사용, SSE forward 시 제거. FastAPI 가 raw exception class name 을 넣어도 사용자에게 노출되지 않음.

### Open Items (후속 PR 후보)

1. `streamQuiz`(기존)를 `aiServiceStreamingWebClient` 로 이전 — 일관성.
2. Exam Studio `contextId` 만료 자동 재발급 retry 1회.
3. Exam Studio `pdfPath` vs `pdfText` 자동 결정 (운영 공유 볼륨 보장 여부에 따라).
4. Classroom Report 학생 수 100명+ 배치 분할 — 현재 1000개 페이지 fetch 후 forward.
5. Student Report Chatbot 대화 이력 영속화 (현재는 stateless — FE 가 messages 매번 보냄).

### 관련 파일

- 인프라: `config/WebClientConfig.java`, `application.yml`
- Bridge: `integration/fastapi/FastApiBridgeClient.java`, `util/sse/SseStreamSupport.java`
- 신규 도메인: `domain/course/discussion/assistant/`, `domain/exam/studio/`, `domain/course/report/criteria/`, `domain/course/report/classroom/`, `domain/course/report/studentchat/`
- 컨트롤러 확장: `domain/course/report/controller/CourseReportController.java`
- 마이그레이션: `db/migration/V3__report_criteria_and_classroom.sql`
- 인계 문서: `docs/handoff/MERGEEDU_AGENT_FEATURES.md` (FastAPI 합의 사항 기록)

---

## [2026-05-09] 학생-선생 상호작용 기능 신규 도입 — Notice / Discussion / Attendance

### 배경

레퍼런스 (`uou-capstone/UOU_Capstone_Design_AI` 의 `new` 브랜치 `MergeEduAgentFull/`) 에는 있지만 우리 v3 에 없던 학생-선생 상호작용 API 를 이식했다. 충돌(invitation/join-request, 평면 course→lecture, exam 모델)은 우리 모델 유지. 에이전트 5종은 FastAPI 합의 필요 → `docs/handoff/MERGEEDU_AGENT_FEATURES.md` 로 분리.

### 신규 도메인

- **`domain/course/notice/`** — 공지사항 + 댓글. 교사 작성, ACTIVE 수강생 알림 (`NOTICE_PUBLISHED`). 댓글은 학생 작성 가능. 1단계 답글 (`NOTICE_COMMENT_REPLIED`).
- **`domain/course/discussion/`** — 토론·자유게시판 + 댓글. 학생도 작성. viewCount 증가, allowComments 토글. 알림 `DISCUSSION_COMMENT_RECEIVED`.
- **`domain/course/attendance/`** — 명시 출석. 회차(lecture 매핑 OR 독립) + 학생별 record. 회차 생성 시 ACTIVE 수강생 ABSENT record 자동 일괄. PUT 일괄 저장은 분산 락 + TransactionTemplate (CourseJoinRequest 패턴).

### 공통 헬퍼 신설

- **`domain/course/service/CourseAccessService`** — `loadCourseAsTeacher` / `loadCourseAsParticipant` / `ensureAuthor` / `ensureAuthorOrCourseTeacher` / `isCourseTeacher`. **중요**: `currentUserResolver.getTeacher()` 직접 호출 시 학생에 대해 `MEMBER_NOT_FOUND` 가 떨어져 권한 의미상 어긋난다 → `getUser()` 받아서 role 부재 시 `FORBIDDEN` 으로 던진다.
- **`common/util/NotificationBodyFormatter.summarize(text, maxLen)`** — 댓글 알림 body 100자 truncate. notification 도메인이 아니라 common 에 둠 (도메인 의존 회피).

### `EnrollmentRepository` 보강

- `existsByStudentAndCourseAndStatus(student, course, EnrollmentStatus.ACTIVE)` — 신규 도메인은 ACTIVE 만 허용 (`COMPLETED`/`DROPPED` 차단).
- `findByCourseAndStatusWithStudentUser(course, ACTIVE)` — 알림 발송·출석 자동 생성·summary 에서 student.user 까지 자주 접근하므로 JOIN FETCH 명시. `existsByStudentAndCourse` 는 다른 도메인이 사용 중이라 그대로 유지.

### NotificationType enum 확장 (DDL 변경 없음)

`notifications.type` 칼럼은 `@Enumerated(STRING)` 으로 `varchar(40)` → 신규 enum 추가만으로 충분:
- `NOTICE_PUBLISHED`, `NOTICE_COMMENT_REPLIED`, `DISCUSSION_COMMENT_RECEIVED`

`resourceType` 문자열은 대문자 고정: `"NOTICE"` / `"DISCUSSION"` / `"ATTENDANCE"`.

### Flyway V2 마이그레이션

`src/main/resources/db/migration/V2__course_interaction_features.sql` — 6개 테이블 일괄:
- `notices`, `notice_comments`
- `discussions`, `discussion_comments`
- `attendance_sessions`, `attendance_records`

특이 사항:
- `parent_comment_id` FK 는 `ON DELETE CASCADE` (부모 댓글 삭제 시 답글 자동 정리). 엔티티에는 `@OnDelete(action = CASCADE)` 도 명시 — H2 (`ddl-auto: create-drop`, Flyway 비활성화) 테스트 환경에서도 동일 동작 보장.
- `attendance_sessions.lecture_id` FK 는 `ON DELETE SET NULL` — lecture 삭제 시 출석 세션은 보존 (의도된 동작, README 명시).
- `attendance_records (attendance_session_id, student_id)` UNIQUE — 중복 record 차단 + 동시 insert 방어.

### 하지 말아야 했던 결정

- **Course 엔티티에 역참조 컬렉션 추가 X** — `notices`/`discussions`/`attendanceSessions` 같은 OneToMany 컬렉션을 Course 에 두면 cascade 영향면이 광범위해진다. 강의실 삭제 시 자식 정리는 V2 DDL 의 `ON DELETE CASCADE` 로 처리.
- **첨부파일 미지원** — 본문 markdown 만. Material PDF 인프라 재사용 검토는 후속 PR.
- **Notice 예약 발행 / Discussion view debounce / 댓글 좋아요 미지원** — 후속 PR 후보.

### 테스트 환경 한계 (V2 DDL 검증 불가능)

`application-test.yml` 은 H2 (`MODE=MySQL`) + `ddl-auto: create-drop` + `spring.flyway.enabled: false`. 따라서 V2 SQL 자체는 단위 테스트로 검증 불가 — Hibernate 가 엔티티 매핑으로 스키마를 생성한다. 운영(MySQL) 부팅 시 `ddl-auto: validate` 가 V2 DDL 과 엔티티 매핑이 일치하는지 검증.

### 관련 파일

- 도메인: `domain/course/{notice,discussion,attendance}/` (각 README 있음)
- 헬퍼: `domain/course/service/CourseAccessService.java`, `common/util/NotificationBodyFormatter.java`
- 마이그레이션: `src/main/resources/db/migration/V2__course_interaction_features.sql`
- 핸드오프: `docs/handoff/MERGEEDU_AGENT_FEATURES.md` (에이전트 5종 — FastAPI 합의 필요)
- 테스트: `src/test/java/.../course/{notice,discussion,attendance,service}/...` 7개

---

## [2026-03-31] N+1 쿼리 문제 — 강의 에이전트 API

### 증상

강의 에이전트 관련 API(`/api/lectures/{lectureId}/stream/*`, `/generate-content` 등)를
호출할 때마다 Hibernate 로그에 아래와 같이 **동일한 테이블을 반복 조회**하는 쿼리가 찍혔다.

```
Hibernate: select l1_0.lecture_id, ... from lectures l1_0 where l1_0.lecture_id=?
Hibernate: select c1_0.course_id, ... from courses c1_0 where c1_0.course_id=?
Hibernate: select t1_0.teacher_id, ... from teachers t1_0 where t1_0.teacher_id=?
```

API 한 번 호출에 lectures → courses → teachers 순으로 3회 SELECT가 연속으로 발생했다.
요청이 많아질수록 DB 부하가 선형이 아닌 **곱셈**으로 늘어나는 전형적인 N+1 패턴이었다.

---

### 원인 분석

#### 엔티티 연관관계 설정

```java
// Lecture.java
@ManyToOne(fetch = FetchType.LAZY)   // ← 지연 로딩
@JoinColumn(name = "course_id")
private Course course;

// Course.java
@ManyToOne(fetch = FetchType.LAZY)   // ← 지연 로딩
@JoinColumn(name = "teacher_id")
private Teacher teacher;
```

`Lecture.course`와 `Course.teacher` 모두 `FetchType.LAZY`로 선언되어 있었다.
JPA의 지연 로딩은 해당 필드에 **실제로 접근하는 순간** 추가 SELECT를 실행한다.

#### 서비스 코드 흐름

`LegacyLectureFlowService`의 6개 메서드(`generateAiContent`, `initializeLectureStream`,
`getNextLectureStreamContent`, `getLectureStreamSession`, `answerLectureStreamQuestion`,
`cancelLectureStream`)는 모두 아래 패턴을 따르고 있었다.

```java
// 1번째 SELECT: lectures 테이블
Lecture lecture = lectureRepository.findById(lectureId)
        .orElseThrow(...);

// 2번째 SELECT: lecture.course 지연 로딩 → courses 테이블
// 3번째 SELECT: course.teacher 지연 로딩 → teachers 테이블
validateLectureParticipant(lecture.getCourse());
// 또는: lecture.getCourse().getTeacher().getId()
```

`lectureRepository.findById()`는 `Lecture`만 조회한다.
이후 `lecture.getCourse()`가 호출되면 `courses` 테이블을 한 번 더 조회하고,
`course.getTeacher()`가 호출되면 `teachers` 테이블을 또 한 번 조회한다.
결국 API 요청 1건당 **3번의 SELECT**가 발생하고 있었다.

#### 기존 User 조회는 이미 최적화되어 있었음

`CurrentUserResolver`는 `@RequestScope` 캐시와 `@EntityGraph({"student","teacher"})`를
이미 적용해 User → Student, Teacher를 단일 JOIN 쿼리로 가져오고 있었다.
즉, 유저 쪽 N+1은 이전에 해결된 상태였고, **Lecture → Course → Teacher 체인이 미처 잡히지 않은 상태**였다.

---

### 수정 방법

#### 1. `LectureRepository`에 `findByIdWithCourse` 추가

```java
@Query("SELECT l FROM Lecture l JOIN FETCH l.course c JOIN FETCH c.teacher WHERE l.id = :id")
Optional<Lecture> findByIdWithCourse(@Param("id") Long id);
```

JPQL `JOIN FETCH`로 `Lecture`, `Course`, `Teacher`를 **단일 쿼리 1번**으로 모두 가져온다.
실행되는 SQL은 아래와 같다.

```sql
SELECT l.*, c.*, t.*
FROM lectures l
INNER JOIN courses c ON l.course_id = c.course_id
INNER JOIN teachers t ON c.teacher_id = t.teacher_id
WHERE l.lecture_id = ?
```

#### 2. `LegacyLectureFlowService` — Course 접근이 있는 메서드 교체

| 메서드 | 변경 전 | 변경 후 |
|---|---|---|
| `generateAiContent` | `findById` | `findByIdWithCourse` |
| `initializeLectureStream` | `findById` | `findByIdWithCourse` |
| `getNextLectureStreamContent` | `findById` | `findByIdWithCourse` |
| `getLectureStreamSession` | `findById` | `findByIdWithCourse` |
| `answerLectureStreamQuestion` | `findById` | `findByIdWithCourse` |
| `cancelLectureStream` | `findById` | `findByIdWithCourse` |

Course·Teacher 접근이 없는 `saveAiContentCallback`, `updateLectureStatusToFailed`,
`getLectureAiStatus`는 불필요한 JOIN 비용을 피하기 위해 기존 `findById`를 그대로 유지했다.

---

### 수정 결과

강의 에이전트 API 1회 호출 기준:

| 구분 | 수정 전 | 수정 후 |
|---|---|---|
| lectures 조회 | 1회 | 1회 (JOIN 포함) |
| courses 조회 | 1회 (lazy) | 0회 |
| teachers 조회 | 1회 (lazy) | 0회 |
| **합계** | **3회** | **1회** |

스트리밍 흐름처럼 `initialize → next → answer → cancel` 4단계를 모두 호출하면
수정 전 12회 SELECT → 수정 후 4회 SELECT로 줄어든다.

---

### 관련 파일

- `domain/course/lecture/repository/LectureRepository.java` — `findByIdWithCourse` 추가
- `domain/course/lecture/service/LegacyLectureFlowService.java` — 6개 메서드 교체

---

## [2026-03-31] N+1 쿼리 추가 제거 — RateLimitInterceptor DB 조회 제거

### 증상

강의 에이전트 API 요청 로그에 위 N+1 수정 이후에도 여전히 아래 패턴이 남아 있었다.

```
Hibernate: select u1_0.user_id, ... from users u1_0 where u1_0.email=?   ← 첫 번째 users 쿼리
Hibernate: select s1_0.student_id, ... from students s1_0 ...            ← lazy
Hibernate: select t1_0.teacher_id, ... from teachers t1_0 ...            ← lazy
```

`CurrentUserResolver`가 이미 `@RequestScope` 캐시와 `@EntityGraph`로 최적화되어 있는데,
요청마다 추가로 `users` 단순 SELECT가 찍히는 이유를 추적했다.

### 원인 분석

`RateLimitInterceptor.preHandle()` → `extractUserId(email)` 안에서
`userRepository.findByEmail(email)` 로 userId를 조회하고 있었다.

```java
// 기존 코드 (문제)
private Long extractUserId(String userEmail) {
    return userRepository.findByEmail(userEmail)   // SELECT users
            .map(user -> user.getId())
            .orElse(null);
}
```

`isAllowed(Long userId)` 의 Redis 키를 `"user:" + userId` 형태로 쓰기 위해
매 요청마다 DB에서 userId를 가져오고 있었다.
userId를 얻으려는 목적 외에 `user.getStudent()` / `user.getTeacher()` 접근은 없지만,
이 쿼리 자체가 **불필요한 SELECT**였다.

### 수정 방법

이메일 자체를 Rate Limit 키로 사용하도록 변경했다.
JWT SecurityContext에 이미 이메일이 있으므로 DB를 전혀 조회하지 않아도 된다.

**`RateLimitService`에 이메일 키 메서드 추가:**

```java
public boolean isAllowedByEmail(String email) {
    return isAllowedInternal("email:" + email, DEFAULT_MAX_REQUESTS_PER_MINUTE);
}
```

**`RateLimitInterceptor` 단순화:**

```java
// 변경 후
String userEmail = authentication.getName();    // SecurityContext (DB 조회 없음)
if (!rateLimitService.isAllowedByEmail(userEmail)) {
    throw new BusinessException(CommonErrorCode.RATE_LIMIT_EXCEEDED);
}
```

`UserRepository` 의존성도 인터셉터에서 완전히 제거되었다.

### 수정 결과

| 구분 | 수정 전 | 수정 후 |
|---|---|---|
| RateLimitInterceptor DB 쿼리 | 1회 (findByEmail) | **0회** |
| 기존 N+1 수정 포함 총 쿼리 | 6회/요청 | 5회/요청 |

> **남은 과제**: `findByIdWithCourse` 코드 변경분은 반드시 **재빌드 후 배포**해야 ④⑤(courses, teachers lazy) 2개가 추가로 제거된다.

### 관련 파일

- `config/RateLimitInterceptor.java` — DB 조회 제거, `isAllowedByEmail` 사용
- `service/RateLimitService.java` — `isAllowedByEmail(String email)` 추가

---

## [2026-03-31] stream/next 실시간 SSE 스트리밍 전환

### 배경 및 문제

강의 에이전트 `/stream/next` API 테스트 중 아래 동작을 확인했다.

- FastAPI는 `/api/v2/lectures/generate-stream` 에서 텍스트를 **NDJSON 청크 50개+** 로 방출
- Spring은 이를 `.reduce(StringBuilder::append)` 로 전부 모은 뒤 **단건 JSON 하나** 로 반환
- 프론트엔드는 AI가 생성 완료될 때까지 대기 후 전체 대본을 한 번에 받음

결과적으로 프론트엔드에서 **타이핑되듯이 글자가 나타나는 스트리밍 UX** 가 불가능했다.

### 변경 구조

```
변경 전:
FE → POST /stream/next → Spring(reduce) → FastAPI NDJSON → Spring 단건 JSON → FE

변경 후:
FE → GET /stream/next (SSE) → Spring(중계) → FastAPI NDJSON 청크 → SSE 이벤트 → FE (실시간)
```

### SSE 이벤트 규격 (FE ↔ Spring)

| event | data 예시 | 설명 |
|---|---|---|
| `message` | `{"type":"delta","delta":"텍스트 조각"}` | FastAPI 청크 1개 |
| `done` | `{"type":"done","lectureId":22,"hasMore":false,"waitingForAnswer":false}` | 스트림 완료 |
| `done` | `{"type":"done","status":"WAITING_FOR_ANSWER","waitingForAnswer":true,"aiQuestionId":"..."}` | AI 질문 대기 |
| `error` | `{"type":"error","message":"..."}` | 오류 |

### 수정 내용

**`FastApiDelegatorClient`에 `streamLectureContent` 추가**
- 기존 `callLectureGenerate` 는 `.reduce()` 로 블로킹
- 신규 `streamLectureContent` 는 `Flux<String>` 으로 청크를 그대로 방출 (reduce 없음)

**`LegacyLectureFlowService`에 `streamNextContent` 추가**
- 동기 DB 작업(강의 조회·권한 검사·PDF 자료 조회) 완료 후 `Flux` 반환
- 각 delta → `event=message` SSE 이벤트
- 스트림 종료 시 → `event=done` 자동 방출
- `WAITING_FOR_ANSWER` / 기타 오류도 SSE 이벤트로 정규 처리

**`LegacyLectureFlowController` stream/next 변경**

| | 변경 전 | 변경 후 |
|---|---|---|
| HTTP 메서드 | `POST` | `GET` |
| 반환 타입 | `ResponseEntity<StreamingContentResponse>` | `Flux<ServerSentEvent<Map>>` |
| Content-Type | `application/json` | `text/event-stream` |

### 프론트엔드 구현 참고

```javascript
const es = new EventSource(`/api/lectures/${lectureId}/stream/next`, {
  headers: { Authorization: `Bearer ${token}` }  // fetch + ReadableStream 권장
});
// 또는 fetch SSE 방식 (Authorization 헤더 전송 가능)
const res = await fetch(`/api/lectures/${lectureId}/stream/next`, {
  headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' }
});
const reader = res.body.getReader();
// delta 이벤트마다 텍스트 버퍼에 append
// done 이벤트 수신 시 스트림 종료 처리
```

> `EventSource` 는 기본적으로 Authorization 헤더를 설정할 수 없으므로 `fetch` + `ReadableStream` 방식을 권장한다.

### 관련 파일

- `integration/fastapi/FastApiDelegatorClient.java` — `streamLectureContent` 추가
- `domain/course/lecture/service/LegacyLectureFlowService.java` — `streamNextContent` 추가, 구 `getNextLectureStreamContent` · `findQuestionTextInSession` 제거
- `domain/course/lecture/controller/LegacyLectureFlowController.java` — GET SSE로 교체
- `FRONTEND_V2_V3_API.md` — 9번 섹션 stream/next SSE 규격 반영

---

## [2026-03-31] 추가 최적화 — 사용자 조회 쿼리 축소 및 강의 스트림 요청 파라미터 유연화

### 1) `findByEmail` 전역 조회 최적화

#### 문제
`CurrentUserResolver` 이외의 서비스들에서 `userRepository.findByEmail()` 호출 후
`getStudent()`/`getTeacher()` 접근이 이어지며 추가 SELECT가 발생했다.

#### 조치
`UserRepository.findByEmail` 자체에 `@EntityGraph({"student","teacher"})`를 적용해
호출 지점 수정 없이도 기본적으로 role 연관 엔티티를 함께 로딩하도록 변경했다.

#### 기대 효과
- 동일 이메일 조회 패턴에서 users → students/teachers 추가 쿼리 감소
- 여러 도메인 서비스(시험/자료/권한 유틸 등)에서 공통적으로 효과

### 2) `page_number` 하드코딩 제거

#### 문제
`FastApiDelegatorClient.buildLectureGenerateRequest()`가
`page_number=1`, `chapter_title=\"페이지 설명\"`, `detail=\"NORMAL\"`을 고정으로 넣고 있었다.

#### 조치
- payload에 `page_number`/`pageNumber`, `chapter_title`/`chapterTitle`, `detail`이 오면 우선 사용
- 누락 시에만 기존 기본값 사용

#### 기대 효과
- 향후 페이지 진행/챕터 제어가 필요할 때 컨트롤러·서비스 payload 확장만으로 대응 가능
- 하드코딩 의존도를 낮춰 FastAPI 요청 스키마와의 정합성 개선

### 관련 파일

- `domain/user/repository/UserRepository.java` — `findByEmail`에 `@EntityGraph` 적용
- `integration/fastapi/FastApiDelegatorClient.java` — 요청 빌더의 하드코딩 제거 및 payload 우선 처리

---

## [2026-04-01] 운영 로그 대응 — stream/next 메서드 호환 및 사용자 조회 쿼리 추가 축소

### 증상

운영 로그에서 아래 두 문제가 반복 확인됨:

1. `HttpRequestMethodNotSupportedException: Request method 'GET' is not supported`
2. `users -> students -> teachers` 조회 패턴이 요청마다 반복

### 원인

- `stream/next`를 GET SSE로 전환한 뒤, 일부 클라이언트/캐시 경로에서 여전히 이전 방식과 혼재되어 메서드 미스매치가 발생.
- 권한 유틸(`AuthorizationUtil.getCurrentUserId`)에서 `findByEmail` 경로를 사용해 불필요한 연관 조회가 누적.

### 조치

1) **`stream/next` 메서드 호환 처리**
- `LegacyLectureFlowController`에서 `/stream/next`를 `GET, POST` 둘 다 수용하도록 변경
- 반환은 동일하게 `text/event-stream`

2) **사용자 조회 쿼리 강제 fetch-join**
- `UserRepository.findByEmail`를 명시적 `LEFT JOIN FETCH(student, teacher)` JPQL로 변경
- `AuthorizationUtil.getCurrentUserId()`를 `findByEmailWithRoles()` 사용으로 변경

### 기대 효과

- 메서드 불일치(405)로 인한 SSE 연결 실패 완화
- 사용자 정보 접근 시 발생하던 `users` 단건 + `students/teachers` 추가 조회 패턴 감소

### 관련 파일

- `domain/course/lecture/controller/LegacyLectureFlowController.java`
- `domain/user/repository/UserRepository.java`
- `util/AuthorizationUtil.java`

---

## [2026-04-01] 예외 로깅 보강 — 405/406 원인 추적 가능하도록 개선

### 증상

운영 로그에 아래가 계속 반복되었으나, 어떤 경로에서 발생했는지 식별이 어려웠다.

- `HttpRequestMethodNotSupportedException: Request method 'GET' is not supported`
- `HttpMediaTypeNotAcceptableException: No acceptable representation`

### 조치

`GlobalExceptionHandler`에 전용 핸들러를 추가해 경로/메서드/허용 메서드/Accept를 명시적으로 기록하도록 변경.

- `handleHttpRequestMethodNotSupportedException` (405)
  - 로그: method, path, supportedMethods
- `handleHttpMediaTypeNotAcceptableException` (406)
  - 로그: method, path, Accept 헤더

### 기대 효과

- "어느 API가 실제로 405를 내는지"를 즉시 특정 가능
- stream/next 외의 다른 경로(예: GET으로 POST 전용 API 호출)와 구분 가능

### 관련 파일

- `config/GlobalExceptionHandler.java`

---

## [2026-04-01] stream/next 프론트 쿼리 스펙 대응 (`pageNumber`, `page`, `userMessage`)

### 배경

프론트 요청 스펙:
- `GET /api/lectures/{lectureId}/stream/next?pageNumber=1&page=1`
- 필요 시 `userMessage` 포함

일부 환경에서 405가 반복되어, 컨트롤러/서비스 매핑과 쿼리 수용 여부를 명시적으로 정리했다.

### 조치

1) `LegacyLectureFlowController.streamNextLectureContent`
- `pageNumber`, `page`, `userMessage`를 `@RequestParam`으로 수용
- `pageNumber` 우선, 없으면 `page`를 사용하는 `effectivePage`로 정규화
- 서비스 호출 시 함께 전달

2) `LegacyLectureFlowService.streamNextContent`
- 시그니처를 `(lectureId, pageNumber, userMessage)`로 확장
- FastAPI payload에 아래를 선택적으로 포함:
  - `page_number`, `pageNumber`
  - `user_message`, `userMessage`

3) `FastApiDelegatorClient.buildLectureGenerateRequest`
- `user_message`를 FastAPI 요청 바디에 전달하도록 확장

### 관련 파일

- `domain/course/lecture/controller/LegacyLectureFlowController.java`
- `domain/course/lecture/service/LegacyLectureFlowService.java`
- `integration/fastapi/FastApiDelegatorClient.java`

---

## [2026-04-01] JWT 필터 `RuntimeException` 미처리 버그 — `4010` 오진단

### 증상

`POST /api/learning/sessions/{lectureId}` 에서 `code: 4010` (`UNAUTHORIZED`, "인증되지 않은 사용자입니다.") 401 응답.
프론트가 `Authorization: Bearer <token>` 을 정상적으로 전송하는데도 발생.

### 원인

`JwtTokenProvider.getAuthentication()` 내부에서 토큰에 `role` 클레임이 없으면 `RuntimeException`을 던진다:

```java
if (claims.get("role") == null) {
    throw new RuntimeException("권한 정보가 없는 토큰입니다.");
}
```

`JwtAuthenticationFilter` 의 catch 블록은 `ExpiredJwtException`, `JwtException | IllegalArgumentException` 만 처리하므로 `RuntimeException` 은 잡히지 않는다.
→ `exception` 속성이 설정되지 않은 채 필터 체인이 끊김
→ `RestAuthenticationEntryPoint` 가 기본값 `UNAUTHORIZED(4010)` 를 반환
→ 마치 토큰이 없는 것처럼 보이는 오진단 발생

**재현 조건:** 리프레시 토큰(`role` 클레임 없음)을 액세스 토큰 자리에 사용하는 경우.

### 수정

`JwtAuthenticationFilter` catch 블록에 `Exception` 처리 추가:

```java
} catch (Exception e) {
    log.warn("[JWT] 토큰 처리 중 예외 발생: path={}, error={}", request.getRequestURI(), e.getMessage());
    request.setAttribute("exception", "INVALID_TOKEN");
}
```

→ 이제 `role` 클레임 없는 토큰은 `4010` 이 아닌 `4013`(`INVALID_TOKEN`) 으로 명확하게 응답

### 수정 파일

- `security/jwt/JwtAuthenticationFilter.java`

---

## [2026-04-01] N+1 쿼리 — MaterialGenerationService (강의 자료 에이전트)

### 증상

v3 통합에이전트 테스트 중 Hibernate 로그에 아래와 같은 N+1 패턴이 관찰됐다.

```
SELECT * FROM generation_sessions WHERE lecture_id=? AND user_id=?
SELECT * FROM lectures    WHERE lecture_id=?        ← lazy load (N+1 #1)
SELECT * FROM courses     WHERE course_id=?         ← lazy load (N+1 #2)
SELECT * FROM teachers    WHERE user_id=?           ← lazy load (N+1 #3)
SELECT * FROM students    WHERE user_id=?           ← lazy load (N+1 #4)
```

### 원인

`MaterialGenerationService`의 모든 메서드가 `generationSessionRepository.findById(id)` 로
`GenerationSession`을 가져온 뒤 `AuthorizationUtil.requireLectureOwner(user, session.getLecture())`
를 호출한다. `requireLectureOwner` 내부에서:

```java
lecture.getCourse().getTeacher().getUser().getId()
```

이 체인이 `Lecture → Course → Teacher` 를 모두 lazy loading하여 쿼리 3~4개가 추가 발생했다.

### 수정

`GenerationSessionRepository`에 JOIN FETCH 전용 쿼리 2개를 추가하고, 서비스에서 교체했다.

**추가된 Repository 메서드:**

```java
// 단건 조회: Lecture·Course·Teacher 한 번에 JOIN FETCH
@Query("""
        SELECT gs FROM GenerationSession gs
        JOIN FETCH gs.lecture l
        JOIN FETCH l.course c
        JOIN FETCH c.teacher
        WHERE gs.id = :id
        """)
Optional<GenerationSession> findByIdWithLecture(@Param("id") Long id);

// 강의+사용자 최신 세션 조회: 동일한 JOIN FETCH 포함
@Query("""
        SELECT gs FROM GenerationSession gs
        JOIN FETCH gs.lecture l
        JOIN FETCH l.course c
        JOIN FETCH c.teacher
        WHERE gs.lecture.id = :lectureId AND gs.user.id = :userId
        ORDER BY gs.createdAt DESC
        """)
List<GenerationSession> findByLectureAndUserWithLecture(...);
```

**서비스 교체:**
- `generationSessionRepository.findById(id)` → `findByIdWithLecture(id)` (10개소 전체)
- `findTopByLecture_IdAndUser_IdOrderByCreatedAtDesc(...)` → `findByLectureAndUserWithLecture(...).stream().findFirst()`

### 수정 파일

- `domain/material/generation/GenerationSessionRepository.java`
- `domain/material/generation/service/MaterialGenerationService.java`

---

## [2026-04-01] SSE Access Denied — Spring Security ASYNC dispatch 미허용

### 증상

v1 강의 에이전트 `GET /api/lectures/{lectureId}/stream/next` (SSE) 호출 시 다음 에러가 연속 발생:

```
ERROR AuthorizationFilter: AuthorizationDeniedException: Access Denied
ERROR StaticView: Cannot render error page ... response has already been committed.
ERROR dispatcherServlet: Unable to handle the Spring Security Exception because the response is already committed.
```

### 원인

SSE(`text/event-stream`) 응답은 다음 2단계로 처리된다.

1. **최초 요청**: JWT 인증 통과 → `Flux<ServerSentEvent>` 반환 → 스트림 응답 시작
2. **Tomcat async dispatch** (`AsyncContextImpl$AsyncRunnable`): Tomcat이 SSE 바이트를 비동기로 전송하기 위해 내부적으로 Request를 `DispatcherType.ASYNC`로 재dispatch한다.

Spring Security 6의 `AuthorizationFilter`는 **모든 DispatcherType에 대해** 인가를 재검사한다.
ASYNC dispatch 시에는 JWT 토큰이 없으므로 `Anonymous` 사용자로 판단 → `Access Denied`.

이미 SSE 응답 헤더가 전송된 상태에서 403을 쓰려다 `response already committed` 에러가 연쇄 발생.

### 수정

`SecurityConfig.authorizeHttpRequests`에 `DispatcherType.ASYNC`와 `DispatcherType.ERROR`를
허용 규칙으로 추가했다. ASYNC dispatch는 이미 인가된 요청의 **내부 처리**이므로 재검사가 불필요하다.

```java
.authorizeHttpRequests(auth -> auth
    .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()   // SSE async dispatch
    .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()   // Error page dispatch
    // ... 기존 규칙
)
```

### 영향 범위

이 설정은 **경로 기반 보안을 우회하지 않는다**.
- 최초 요청(FORWARD/REQUEST)은 기존 JWT 인가 규칙이 그대로 적용됨
- ASYNC는 이미 최초 요청에서 인증된 후 Tomcat 내부에서 발생하므로 보안 위협 없음
- 영향받는 엔드포인트: `Flux<ServerSentEvent>` 또는 `Flux` 반환하는 모든 SSE 스트리밍 API

### 수정 파일

- `config/SecurityConfig.java`

---

## [2026-04-02] N+1 쿼리 — `users WHERE email=?` 반복 조회

### 증상

여러 서비스가 같은 요청 내에서 각자 `SecurityContextHolder.getContext().getAuthentication().getName()` + `userRepository.findByEmail(email)` 패턴으로 현재 사용자를 조회하여, 단일 요청에 `users WHERE email=?` 쿼리가 최대 11회 이상 발생했다.

```
Hibernate: select u1_0.user_id,...from users u1_0 where u1_0.email=?  ← 1
Hibernate: select u1_0.user_id,...from users u1_0 where u1_0.email=?  ← 2
Hibernate: select u1_0.user_id,...from users u1_0 where u1_0.email=?  ← 3
... (서비스마다 반복)
```

### 원인

`CurrentUserResolver` (`@RequestScope`) 가 이미 구현되어 있었으나, 아래 6개 서비스가 이를 사용하지 않고 직접 `userRepository.findByEmail()`을 호출하고 있었다.

| 서비스 | findByEmail 호출 횟수 |
|---|---|
| `MaterialGenerationService` | 11 |
| `MaterialService` | 3 |
| `ExamSubmissionService` | 2 |
| `ExamGenerationStreamService` | 1 |
| `MaterialGenerationStreamService` | 1 |
| `ExamGenerationService` (게이트웨이 메서드) | 1 |

### 수정

각 서비스에 `CurrentUserResolver currentUserResolver` 필드 주입 후, 아래 2-line 패턴을:

```java
String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
User currentUser = userRepository.findByEmail(userEmail)
        .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
```

아래 1-line으로 교체:

```java
User currentUser = currentUserResolver.getUser();
```

`MaterialService`의 `teacherRepository.findByUser_Id(currentUser.getId())` 도 `currentUserResolver.getTeacher()`로 교체. `UserRepository.findByEmailWithRoles`가 `@EntityGraph`로 Student/Teacher를 한 번에 JOIN FETCH하므로 추가 쿼리 없음.

`ExamGenerationService`의 `generateExam(requestDto, String userEmail)` 내부 메서드는 `@Async` 경로와 공유하므로 `findByEmail` 1회는 유지 (비동기 스레드에서 `@RequestScope` 접근 불가).

### 수정 파일

- `domain/material/generation/service/MaterialGenerationService.java`
- `domain/material/service/MaterialService.java`
- `domain/exam/service/ExamSubmissionService.java`
- `domain/exam/service/ExamGenerationStreamService.java`
- `domain/exam/service/ExamGenerationService.java`
- `domain/material/generation/service/MaterialGenerationStreamService.java`

---

## [2026-04-02] v3 통합 에이전트 — ANSWER_QUESTION `[Errno 21] Is a directory: '.'`

### 증상

v3 통합학습(MergeEduAgent) 세션에서 사용자가 질문을 보내면 FastAPI가:

```
[SYSTEM] Tool execution failed (ToolName.ANSWER_QUESTION): [Errno 21] Is a directory: '.'
```

오류를 반환했다. 에이전트가 응답하지 않고 다음 단계로 넘어갔다.

### 원인

FastAPI의 `ANSWER_QUESTION` 도구는 PDF 파일을 직접 읽어 답변을 생성한다.  
그런데 Spring이 `POST /api/learning/sessions/{lectureId}` 세션 생성 시 `pdf_path`를 FastAPI에 전달하지 않으면, FastAPI가 PDF 경로를 `.`(현재 디렉터리)로 처리하여 파일 대신 디렉터리를 열려다 실패한다.

```
Spring log: hasPdfPath=false  ← pdf_path 미전달
```

### 수정

`LearningSessionService.getOrCreateSession()`에서 `pdfPath` 파라미터가 없으면 `MaterialRepository`로 해당 강의의 최신 PDF 자료 경로를 자동 조회하여 FastAPI에 전달하도록 수정.

```java
if (!StringUtils.hasText(effectivePdfPath)) {
    effectivePdfPath = materialRepository
            .findFirstByLecture_IdAndMaterialTypeOrderByCreatedAtDesc(lectureId, "PDF")
            .map(Material::getFilePath)
            .orElse(null);
}
```

강의에 PDF가 없으면 `null`이 전달되어 FastAPI가 PDF 없는 세션으로 처리한다.

### 수정 파일

- `domain/learning/service/LearningSessionService.java`

---

## [2026-04-16] 보안·성능 개선 계획 — 백엔드 단독 구현 분 (이번 세션)

### 구현 완료

| ID | 항목 | 한줄 설명 | 파일 |
|---|---|---|---|
| 1-7 | show-sql: false (prod) | prod에서 SQL 로그 비활성화 — PII 유출 방지 | `application-prod.yml` |
| 1-10 | 내부정보 노출 차단 | WebClient 에러 응답에서 `statusText` 제거, 고정 메시지 | `GlobalExceptionHandler.java` |
| 1-4 | CORS prod origin 확정 | env 분리 + prod 기본 `https://ai-lms.netlify.app` 단일 origin 확정 | `SecurityConfig.java`, `application-prod.yml` |
| 1-2 | OAuth 토큰 URL 노출 제거 | one-time exchange code (60s, 1회) — redirect 에 `?code=` 만, `POST /api/auth/oauth/exchange` 추가 | `OAuth2AuthenticationSuccessHandler.java`, `OAuthExchangeStore.java`, `AuthService.java`, `AuthController.java` |
| 1-8 | Refresh 토큰 회전 | 매 refresh 마다 jti 재발급 + Redis 화이트리스트, replay 감지 시 모든 refresh 무효화 | `JwtTokenProvider.java`, `RefreshTokenStore.java`, `AuthService.java` |
| 2-2 (1차) | PageResponse + courses + 학생 리포트 페이징 | `PageResponse<T>` 공통 타입, `GET /api/courses` 와 `GET .../reports/students` 에 `Pageable` 적용, size 최대 100 + sort 화이트리스트 | `PageResponse.java`, `PageableSupport.java`, `CourseController.java`, `CourseReportController.java` |
| 2-10 | SSE timeout/done 표준 | message/heartbeat/timeout/error/done 표준 이벤트, idle timeout 60s, FastAPI heartbeat 패스스루 | `SseStreamSupport.java`, `SseEventNames.java`, `LearningSessionService.java`, `ExamGenerationStreamController.java`, `MaterialGenerationStreamController.java` |
| 2-6 | WebClient 커넥션 풀 명시 | ConnectionProvider max=50, idle/lifetime/evict 설정 | `WebClientConfig.java` |
| 2-7 | AsyncConfig CallerRunsPolicy | 3개 Executor 큐 포화 시 caller 스레드 실행 + 경고 로그 | `AsyncConfig.java` |
| 3-6 | @Transactional + 외부 HTTP 분리 | `uploadFile`에서 WebClient를 트랜잭션 밖으로, DB만 별도 메서드 | `MaterialService.java` |
| 3-6 | streamFile 트랜잭션 제거 | readOnly 트랜잭션 제거 — 스트리밍 중 커넥션 점유 방지 | `MaterialService.java` |

### 이전 세션 구현분 (아직 커밋 안 됨)

| ID | 항목 | 한줄 설명 |
|---|---|---|
| 1-1 | JWT 만료 검증 (P0) | `parseClaims`에서 ExpiredJwtException catch 제거 |
| 1-3 | 타이밍 공격 방지 | `AiCallbackController`에 `MessageDigest.isEqual` 적용 |
| 1-6 | Rate Limit + 브루트포스 | `RateLimitInterceptor` log.warn + `AuthRateLimitInterceptor` 신규 |
| 2-1 | N+1 해결 | `CourseRepository.findByIdWithLectures` fetch join + 벌크 조회 |
| 2-3 | 파일 스트리밍 | StreamingResponseBody + DataBufferUtils |
| 2-9 | HikariCP 설정 | pool-size, timeout, leak-detection 명시 |
| 3-1 | JwtTokenProviderTest | 단위 테스트 7개 |

### 프론트 협의 후 진행 (2026-04 라운드 종료 — 모두 "구현 완료" 표로 이동됨)

> 1-2 / 1-4 / 1-8 / 2-2(1차) / 2-10 — Ultraplan 라운드에서 계약 확정 후 구현 반영.
> 후속 라운드용으로 새로 식별된 협의 항목이 있으면 본 표에 등록.

### 남은 백엔드 단독 항목

| ID | 우선순위 | 항목 |
|---|---|---|
| 1-5 | P3 | 비밀번호 정책 (최소 길이/복잡도) |
| 1-9 | N/A | Actuator (의존성 없음 — 조치 불필요) |
| 2-4 | P2 | 폴링 → 콜백/ScheduledExecutor |
| 2-5 | P2 | block() 전용 스레드풀 격리 |
| 3-3 | P3 | MaterialGenerationService 3,922줄 분해 |
| 3-4 | P3 | ddl-auto (캡스톤 범위면 update 유지) |
| 3-5 | P2 | Dockerfile 레이어 최적화 |

---

## [2026-04-18] v2.7 E2E 스모크 테스트 — B-3(시험 생성/채점) 추가

### 배경

`V27_E2E_SMOKE_TEST.md`에는 B-3(시험 생성/채점) 시나리오가 문서화되어 있었으나 `scripts/smoke-v27.ps1` 자동 실행 스크립트에는 B-0 ~ B-1 과 A-1·A-3·A-4·A-5 만 구현되어 있었다. 시험 관련 엔드포인트는 수동 확인에 의존하고 있어 회귀 감지가 어려웠다.

### 조치

`scripts/smoke-v27.ps1`에 다음 두 단계를 추가했다.

1) **B-3a `POST /api/exams/generation` (TEACHER)**
   - `FLASH_CARD` + `targetCount=5` + 최소 `lectureContent` 로 호출
   - 기존 `Check-Not404Or405` 재사용 — 2xx·4xx는 경로 생존으로 PASS, 404/405/타임아웃만 FAIL

2) **B-3b `POST /api/exams/submission` (STUDENT/TEACHER)**
   - 존재하지 않는 `examSessionId=0` 으로 호출하므로 정상 동작 시 404(BusinessException)가 기대 응답
   - `Check-Not404Or405`를 쓰면 404가 FAIL로 찍히는 문제가 있어 **전용 분기** 추가: 405/타임아웃만 FAIL, 그 외 2xx·4xx(404 포함)는 모두 PASS

### 문서 보강

`V27_E2E_SMOKE_TEST.md` B-3 섹션에 스크립트 해석 가이드 소절을 추가해 404가 PASS인 이유를 명시했다.

### 관련 파일

- `scripts/smoke-v27.ps1` — B-3a, B-3b 추가
- `V27_E2E_SMOKE_TEST.md` — B-3 스모크 스크립트 해석 가이드 추가

### 남은 과제

- B-2 (Learning Session SSE 스트림) 는 PowerShell에서 SSE 첫 이벤트 수신·종료 로직이 필요하여 이번 커밋 범위에서 제외. 후속 작업에서 추가 예정.

---

## [2026-04-29] EC2 배포 자동화 — Nginx 포트 전환

### 증상

`develop` 배포 컨테이너는 정상 기동했지만 도메인 헬스 체크가 Nginx에서 502를 반환했다.

```text
https://uouaitutor.duckdns.org/api/health
→ 502 Bad Gateway
```

반면 컨테이너 직접 포트는 정상 응답했다.

```text
http://3.36.233.169:8081/api/health
→ status=UP

http://3.36.233.169:8001/health
→ {"status":"ok","redis":"connected"}
```

### 원인

같은 도메인 `uouaitutor.duckdns.org`를 `main`과 `develop` 배포에 번갈아 사용하지만, Nginx 설정은 운영 포트에 고정되어 있었다.

```nginx
proxy_pass http://127.0.0.1:8080/;
proxy_pass http://127.0.0.1:8000/;
```

`develop` compose는 Spring `8081`, FastAPI `8001`로 노출하므로, 도메인 요청이 실행 중인 develop 컨테이너가 아닌 비활성 main 포트로 전달되어 502가 발생했다.

### 조치

`deploy.sh`에서 대상 브랜치에 따라 compose/env 파일과 함께 Nginx 프록시 포트를 결정하도록 수정했다.

```bash
if [ "$TARGET_BRANCH" = "main" ]; then
  SPRING_PORT="8080"
  AI_PORT="8000"
else
  SPRING_PORT="8081"
  AI_PORT="8001"
fi
```

컨테이너 재기동 후 `/etc/nginx/conf.d/uouaitutor.conf`의 `proxy_pass`를 브랜치별 포트로 갱신하고, `nginx -t` 검증 후 reload한다.

```bash
sudo -n nginx -t
sudo -n systemctl reload nginx
```

또한 EC2에서 실행되는 셸 스크립트가 Windows 작업 환경에서 CRLF로 변환되지 않도록 루트 `.gitattributes`에 `*.sh text eol=lf`를 추가했다.

### 관련 파일

- `deploy.sh`
- `.gitattributes`

---

## [2026-04-29] 인증·SSE·페이징 계약 반영 (Ultraplan 라운드)

### 배경

`fastapi-gemini-wobbly-truffle` plan 의 후속으로, Ultraplan 이 6 개 영역(CORS / OAuth / Refresh / SSE / Paging / 학생 리포트)의 계약을 고정해 돌려줬다. 본 라운드는 그 확정 계약을 코드/설정에 반영한 것이다.

### 1-4 CORS prod origin 확정

- 코드 변경 없음. `application-prod.yml` 의 `cors.allowed-origins` 기본값(`https://ai-lms.netlify.app`) 으로 prod 고정. preview/staging 은 prod BE 미연결.
- `allowCredentials=true` 유지, wildcard origin 미사용.

### 1-2 OAuth one-time exchange code

- `OAuth2AuthenticationSuccessHandler` 가 더 이상 redirect URL 에 토큰을 싣지 않음. 성공 시 `?code=<UUID>`, 실패 시 `?errorCode=<code>` (메시지 문자열 미노출).
- 신규 `POST /api/auth/oauth/exchange` body `{code}` → `TokenResponseDto`. 기존 `RuntimeException` 도 `BusinessException` 으로 정리.
- code 저장: Redisson `RBucket`, 키 `sb:oauth:exchange:{code}`, TTL 60s, `getAndDelete` 로 atomic 1회 소비.

### 1-8 Refresh 토큰 회전

- `JwtTokenProvider.createRefreshToken(email, jti)` 시그니처로 변경 (jti claim 강제). 기존 `createRefreshToken(email)` 제거 → 호출부에서 항상 jti 명시 발급.
- 신규 `RefreshTokenStore` (Redis 화이트리스트, 키 `sb:refresh:{userId}:{jti}`). login / refresh 회전 / OAuth exchange 시 jti 등록.
- `AuthService.refreshToken` 매 호출마다 새 access + 새 refresh 발급, 기존 jti revoke. 화이트리스트 미존재 = replay 의심 → `revokeAllForUser` + 401.
- `AuthService.logout` 에서 access blacklist 외에 해당 user 의 모든 refresh 무효화.
- 마이그레이션: jti 없는 (legacy) refresh 토큰은 401 처리 → 사용자 재로그인. dev 환경이라 별도 마이그레이션 코드 없음.

### 2-2 PageResponse + courses + 학생 리포트 페이징 (1차)

- 신규 `PageResponse<T>` (`content/page/size/totalElements/totalPages/first/last`).
- 신규 `PageableSupport.validate` — size > 100 거부, sort 화이트리스트 강제.
- `GET /api/courses` 페이징 적용 (정렬 허용: createdAt/updatedAt/title, 기본 updatedAt,desc).
- `GET /api/courses/{id}/reports/students` 페이징 적용 + DTO 이름 정리 (`StudentReportListItem`, `StudentReportDetailResponse`). 기존 `sortBy`/`direction` 분리 파라미터는 폐기되고 `sort=field,direction` 단일 파라미터로 통일. 기본 `name,asc`.
- 1차는 in-memory slice — 강의실/학생 카운트 작다는 가정. DB-level 페이징은 2차.

### 2-10 SSE timeout/done 표준

- 신규 `SseEventNames` 상수 + `SseStreamPolicy` 정책 record + `SseStreamSupport` 헬퍼.
- 이벤트 표준: `message` / `heartbeat` / `timeout` / `error` / `done`.
- BE 측 idle timeout 60s. 도달 시 `event:timeout` emit 후 종료. 정상 완료 시 `event:done` append.
- FastAPI heartbeat NDJSON 라인 (`{"type":"heartbeat"}`) 은 더 이상 필터링하지 않고 SSE `event:heartbeat` 로 패스스루.
- 적용: `LearningSessionService` (v3 학습 세션), `ExamGenerationStreamController` (시험 생성), `MaterialGenerationStreamController` (자료 생성 5개 phase). v1 legacy lecture flow 는 "신규 기능 추가 금지" 규칙 준수해 미적용.

### FastAPI 측 합의 대기 항목

- **F-1 (2-10 후속)**: heartbeat 형식/주기 합의. BE 가정: NDJSON `{"type":"heartbeat"}` 30s 이내 주기. 다르면 `idleTimeout` 만 환경변수로 조정.
- **F-2 (G-1 후속, P2)**: `GeminiBridgeClient` 레벨 schema sanitizer 공통화. v2 모듈(`test_gen/**`, `note_gen/**`) 재발 방지용. 별도 PR/티켓 권장.

### 관련 파일

- 신규: `common/dto/PageResponse.java`, `common/web/PageableSupport.java`, `security/jwt/RefreshTokenStore.java`, `security/oauth/OAuthExchangeStore.java`, `domain/user/dto/OAuthExchangeRequestDto.java`, `util/sse/{SseEventNames,SseStreamPolicy,SseStreamSupport}.java`, `domain/course/report/dto/{StudentReportListItem,StudentReportDetailResponse}.java`
- 변경: `security/jwt/JwtTokenProvider.java`, `security/oauth/OAuth2AuthenticationSuccessHandler.java`, `domain/user/service/AuthService.java`, `domain/user/controller/AuthController.java`, `domain/course/{controller/CourseController,service/CourseService}.java`, `domain/course/report/{controller/CourseReportController,service/CourseStudentReportService}.java`, `domain/learning/service/LearningSessionService.java`, `domain/exam/controller/ExamGenerationStreamController.java`, `domain/material/generation/controller/MaterialGenerationStreamController.java`
- 삭제: `domain/course/report/dto/{StudentReportCardDto,CourseStudentReportListResponse,CourseStudentReportDetailResponse}.java`

---

## [2026-04-30] 학생 강의실 입장 흐름 단일화 — 초대코드 단일 경로

### 증상

학생이 강의실에 입장하는 경로가 두 개로 이원화되어 있었다.

- `POST /api/courses/{courseId}/enroll` — 강의실 ID 기반 (`enrollCourse(Long)`)
- `POST /api/courses/join?code={invitationCode}` — 초대코드 기반 (`enrollCourseByCode(String)`)

ID 기반 경로는 학생이 **강의실 ID만 알면 누구나 임의의 강의실에 등록**할 수 있어
초대 모델에 어긋났다. 또 FE/QA 가 두 경로 중 어느 쪽이 정식 계약인지 판단하기 어려운 상태였고,
`course/README.md` 에는 "구버전, 유지" 로 표시된 채 코드만 살아 있는 모호한 상태였다.

### 원인

초기 구현 시점에는 `enrollCourse(Long)` 만 존재했고, 이후 초대 모델로 전환하면서 `enrollCourseByCode(String)` 가 추가됐다. 새 경로 도입 시 기존 ID 기반 경로를 정리하지 않고 "일단 둠" 주석과 함께 남겨두면서 두 경로가 공존하게 되었다.

### 수정 방법

ID 기반 입장 경로를 컨트롤러·서비스 양쪽에서 완전히 제거하고, 초대코드 기반 경로 하나만 남겼다.

```java
// CourseController — 매핑 1개 + 주석 라인 제거
// (제거됨)
// @PostMapping("/{courseId}/enroll")
// public ResponseEntity<String> enrollCourse(@PathVariable Long courseId) { ... }

// 유지
@PostMapping("/join")
@PreAuthorize("hasAuthority('STUDENT')")
public ResponseEntity<String> joinCourse(@RequestParam("code") String invitationCode) {
    enrollmentService.enrollCourseByCode(invitationCode);
    return ResponseEntity.status(HttpStatus.CREATED).body("강의실 입장이 완료되었습니다.");
}
```

```java
// EnrollmentService — enrollCourse(Long) 메서드 전체 제거
// enrollCourseByCode(String) 만 남음
```

### 계약 영향

| 상황 | 예외 코드 | HTTP |
|---|---|---|
| 잘못된 초대코드 | `INVALID_INVITATION_CODE` | 400 |
| 이미 수강 중 | `DUPLICATE_RESOURCE` | 409 |
| 제거된 경로 호출 | (Spring 기본) Not Found | 404 |

`Course.invitationCode` 필드, `CourseService.createCourse()` UUID 자동 발급, `CourseResponseDto.invitationCode`
응답 노출은 모두 그대로다. 선생님이 강의실 생성 시 자동 발급된 코드를 학생에게 공유하는 UX 는 동일하게 유지된다.

DB 마이그레이션은 없다. 기존 `enrollments` 데이터는 입장 경로와 무관하게 `(student, course)` 유니크 제약만으로 동작하므로 그대로 유효하다.

### 관련 파일

- `uou-capstone/src/main/java/io/github/uou_capstone/aiplatform/domain/course/controller/CourseController.java`
- `uou-capstone/src/main/java/io/github/uou_capstone/aiplatform/domain/course/service/EnrollmentService.java`
- `uou-capstone/src/main/java/io/github/uou_capstone/aiplatform/domain/course/README.md`

---

## [2026-04-30] 강의실 승인형 등록 도입 (CourseJoinRequest 1단계)

### 배경

기존 학생 입장 흐름은 초대 코드 입력 → 즉시 Enrollment 생성이었다. 교사가 누가 들어왔는지 관여할 수 없고, 코드를 알면 누구나 등록되는 구조라 "교사 승인 후 등록" 모델이 필요했다.

이번 라운드는 그 1단계 — **course 도메인의 승인형 등록(CourseJoinRequest)** 만 구현한다. 2단계(learning 세션 입장 토큰 게이트)는 별도 라운드.

### 1단계 핵심 변경

#### 신규 도메인

`CourseJoinRequest` 엔티티 + `CourseJoinRequestStatus` enum (`PENDING`/`APPROVED`/`REJECTED`/`BLOCKED`).
`Course` 엔티티에 `Set<CourseJoinRequest> joinRequests` 컬렉션 추가 (`cascade=ALL`, `orphanRemoval=true`) — 강의실 삭제 시 자동 정리.

```java
@Entity
@Table(name = "course_join_requests",
        indexes = {
                @Index(name = "idx_join_request_course_status", columnList = "course_id, status"),
                @Index(name = "idx_join_request_student_course", columnList = "student_id, course_id")
        })
public class CourseJoinRequest extends BaseTimeEntity {
    // id, student, course, status (PENDING으로 시작)
    public void approve() { ... }
    public void reject()  { ... }
    public void block()   { ... }
}
```

DB 유니크 제약은 **두지 않음**. `(student, course, status)` 같은 제약을 걸면 REJECTED → PENDING → REJECTED 반복 정책과 충돌. 중복 PENDING 차단은 서비스 레이어의 `existsByStudentAndCourseAndStatus`로 처리.

#### 신규 API (`CourseJoinRequestController`)

| 메서드/경로 | 권한 | 동작 |
|---|---|---|
| `POST /api/courses/join-requests` | STUDENT | body `{ invitationCode }` → PENDING 생성 |
| `GET /api/courses/{courseId}/join-requests?status=PENDING` | TEACHER | PageResponse, 기본 createdAt,desc |
| `POST /api/courses/{courseId}/join-requests/{requestId}/approve` | TEACHER | Enrollment 생성 + APPROVED |
| `POST /api/courses/{courseId}/join-requests/{requestId}/reject` | TEACHER | REJECTED |
| `POST /api/courses/{courseId}/join-requests/{requestId}/block` | TEACHER | BLOCKED |

#### 학생 요청 검증 순서 (서비스 레이어)

1. invitationCode → course 조회 (없으면 `INVALID_INVITATION_CODE` 400)
2. 이미 Enrollment 있음 → `ENROLLMENT_ALREADY_EXISTS` (409)
3. BLOCKED 이력 있음 → `JOIN_REQUEST_BLOCKED` (403)
4. PENDING 이력 있음 → `JOIN_REQUEST_PENDING_EXISTS` (409)
5. REJECTED 이력만 있음 → 새 PENDING 생성 허용 (재요청)

#### 교사 처리 검증

- 본인 소유 강의실인지 확인 (`course.teacher.id == currentTeacher.id`) — 아니면 `FORBIDDEN`
- requestId가 해당 courseId 소속 + 상태 PENDING — 아니면 `JOIN_REQUEST_NOT_FOUND` 또는 `JOIN_REQUEST_ALREADY_PROCESSED`
- approve 시: Enrollment가 이미 있으면 요청만 APPROVED, 없으면 새로 생성 (이중 안전망)

#### 신규 에러 코드 (`CommonErrorCode`)

| 코드 | HTTP | code |
|---|---|---|
| `JOIN_REQUEST_BLOCKED` | 403 | 4031 |
| `JOIN_REQUEST_NOT_FOUND` | 404 | 4053 |
| `JOIN_REQUEST_PENDING_EXISTS` | 409 | 4093 |
| `JOIN_REQUEST_ALREADY_PROCESSED` | 409 | 4094 |
| `ENROLLMENT_ALREADY_EXISTS` | 409 | 4095 |

#### 호환 계층

기존 `POST /api/courses/join?code=...`는 **그대로 유지**(즉시 Enrollment 생성). FE 전환 부담을 줄이려는 결정. Swagger `@Operation(deprecated = true)` 마킹 + 설명에 "신규 승인형 흐름은 /api/courses/join-requests 사용" 명시.

#### Repository 메서드

- `existsByStudentAndCourseAndStatus(Student, Course, Status)` — 중복 PENDING/BLOCKED 차단용
- `findByCourseIdAndStatusWithStudent(courseId, status, pageable)` — JOIN FETCH `r.student JOIN FETCH s.user`로 N+1 방지. `countQuery`를 별도 명시해 페이지 카운트 정합성 확보 (fetch join은 카운트 쿼리에 포함하면 부적절)
- `findByIdAndCourseId(Long, Long)` — 승인/거절/차단 시 path 일관성 검증

### 보류 (다음 라운드)

- **2단계**: lectureId 기반 세션 입장 토큰 회전 게이트 (`POST /api/learning/sessions/{lectureId}` 앞단)
- 학생 본인의 PENDING/REJECTED/BLOCKED 상태 조회 API — 폴링 필요해지면 추가
- 교사 → 학생 승인 알림 (notification 인프라 부재)
- 호환 경로(`POST /api/courses/join?code=`) 제거 — FE 전환 완료 후

### DB 마이그레이션

Flyway 미사용. `ddl-auto=create`(local) / `update`(prod)로 `course_join_requests` 테이블 자동 생성. 인덱스 2개(`idx_join_request_course_status`, `idx_join_request_student_course`)는 엔티티 `@Index`로 정의.

### 관련 파일

- 신규:
  - `domain/course/entity/CourseJoinRequest.java`
  - `domain/course/entity/CourseJoinRequestStatus.java`
  - `domain/course/repository/CourseJoinRequestRepository.java`
  - `domain/course/service/CourseJoinRequestService.java`
  - `domain/course/controller/CourseJoinRequestController.java`
  - `domain/course/dto/CourseJoinRequestCreateDto.java`
  - `domain/course/dto/CourseJoinRequestResponseDto.java`
  - `domain/course/dto/CourseJoinRequestListItemDto.java`
- 변경:
  - `domain/course/entity/Course.java` — `joinRequests` 컬렉션 추가
  - `domain/course/controller/CourseController.java` — `/join` 핸들러 `@Operation(deprecated = true)` 마킹
  - `common/error/CommonErrorCode.java` — 5개 코드 추가
  - `domain/course/README.md` — 가입 요청 흐름 섹션 추가, 주요 파일 갱신

---

## [2026-04-30] 학생 가입 요청 상태 조회 + 범용 알림 인프라

### 증상

직전 라운드(`8b31415`)에서 강의실 승인형 등록(CourseJoinRequest)이 도입되었지만 학생 측 UX가 비어 있었다.

- 학생이 신청 후 **자기 요청의 상태(PENDING/APPROVED/REJECTED/BLOCKED)를 조회할 수단이 없음** — FE는 `POST /api/courses/join-requests`에 의존했지만 그 응답은 1회성이고 이후 상태 변화를 알 길 없음
- 교사가 승인/거절/차단해도 **학생이 결과를 받는 채널 없음** — DEV_NOTES 직전 항목의 "보류" 섹션에 명시됨 (`교사 → 학생 승인 알림 (notification 인프라 부재)`)

### 원인

- 학생 본인 요청 조회용 Repository/Service 메서드 부재. `findByCourseIdAndStatusWithStudent`는 교사용(courseId 필터)이라 학생이 호출 불가
- 알림 도메인 자체가 미존재. `Notification` 엔티티/저장소/SSE 발행 어느 것도 없었음
- `CourseJoinRequestService.approve/reject/block`는 상태 전이만 수행하고 학생에게 통지하지 않음

### 조치

**1) 학생용 상태 조회 API**
- `CourseJoinRequestRepository.findByStudentIdWithCourse(studentId, pageable)` — `JOIN FETCH r.course`로 N+1 방지 + 전용 `countQuery`
- `CourseJoinRequestService.getMyJoinRequests(Pageable)` — `currentUserResolver.getStudent()` 기반, 기존 `SORT_WHITELIST`/`PageableSupport.validate` 재사용
- `GET /api/courses/join-requests/me` (STUDENT) — 본인 요청 목록 (PageResponse, `MyJoinRequestItemDto`: `requestId`/`courseId`/`courseTitle`/`status`/`requestedAt`/`updatedAt`)

**2) 범용 Notification 도메인 신설** (`domain/notification/`)
- `Notification` 엔티티 — `user`(ManyToOne LAZY) / `type` / `title` / `body` / `resourceType` / `resourceId` / `readAt` / `createdAt(BaseTimeEntity)`. 인덱스 2개: `(user_id, read_at)`(unread count), `(user_id, created_at)`(목록 정렬)
- `NotificationType` — 이번 라운드 발행은 `COURSE_JOIN_APPROVED`/`COURSE_JOIN_REJECTED`/`COURSE_JOIN_BLOCKED` 3종. enum 구조라 다른 도메인이 추후 추가 가능
- `NotificationRepository` — `findByUserId`, `countByUserIdAndReadAtIsNull`, `findByIdAndUserId`(소유권 동시 검증), `@Modifying markAllAsReadByUserId`
- `NotificationService.notify(...)` — DB 저장 후 **`afterCommit`에서만** SSE push (커밋 실패 시 false-push 방지). 트랜잭션 비활성 컨텍스트에서는 즉시 push
- 본인 알림만 접근 가능: `findByIdAndUserId` 미스 시 `RESOURCE_NOT_FOUND` 일관 처리

**3) SSE 실시간 스트림**
- `NotificationStreamRegistry` — `ConcurrentHashMap<userId, CopyOnWriteArrayList<Sinks.Many<...>>>` 다중 sink 구조 (한 사용자 다중 탭/디바이스 대응). `Flux.doFinally`로 cleanup
- `GET /api/notifications/stream` — `Flux<ServerSentEvent<Map<String,Object>>>` 표준 (CLAUDE.md). `SseStreamSupport.wrapEvents(...)` 재사용, `idleTimeout=5분`, `appendDoneOnComplete=false`(영속 알림 스트림은 done으로 종료시키지 않음)
- 운영 규칙: SSE는 실시간 편의용, **진실 원천은 DB**. 미연결 중 발생한 알림은 `GET /api/notifications`로 복구

**4) CourseJoinRequest 처리 시점 발행 wiring**
- `approve/reject/block` 메서드 끝에 `notifyJoinRequestProcessed(...)` 호출. 알림 저장은 같은 트랜잭션 안에서 일어나므로 처리 실패 시 함께 롤백
- 메시지: `"{courseTitle} 강의실 가입이 승인되었습니다."` 등 서버에서 완성된 문장 저장. `resourceType="course"`, `resourceId={courseId}`

### 설계 결정

| 결정 | 사유 |
|---|---|
| WebSocket 미도입 | 단방향 푸시면 충분, SSE가 인프라 가벼움 |
| Redis Pub/Sub 알림 브로드캐스트 미도입 | 단일 인스턴스 운영 가정. 멀티 인스턴스 전환 시 `shared:notification:{userId}` 채널로 확장 |
| `processedAt` 전용 컬럼 미도입 | `BaseTimeEntity.updatedAt`을 처리 시각으로 활용 |
| 알림 메시지 서버 완성 | FE i18n 부재. 서버에서 완성 문장 저장이 단순 |
| 같은 트랜잭션 + afterCommit push | 알림 누락(상태는 바뀌었는데 알림 미저장) 방지 + false-push(롤백됐는데 SSE만 갔음) 방지 |

### 보류 (다음 라운드)

- 멀티 인스턴스 환경 Redis Pub/Sub 알림 브로드캐스트
- material/exam/learning 도메인의 알림 발행 연결 (이번 라운드는 join-request만)
- 알림 카테고리/그룹핑·집계, 푸시(FCM/APNs), 알림 만료/자동 정리 배치
- 호환 경로(`POST /api/courses/join?code=`) 제거 — FE 전환 완료 후

### DB 마이그레이션

Flyway 미사용. `ddl-auto=create`(local) / `update`(prod)로 `notifications` 테이블 자동 생성. 인덱스 2개(`idx_notification_user_read`, `idx_notification_user_created`)는 엔티티 `@Index`로 정의.

### 관련 파일

- 신규:
  - `domain/notification/entity/Notification.java`
  - `domain/notification/entity/NotificationType.java`
  - `domain/notification/repository/NotificationRepository.java`
  - `domain/notification/service/NotificationService.java`
  - `domain/notification/service/NotificationStreamRegistry.java`
  - `domain/notification/controller/NotificationController.java`
  - `domain/notification/dto/NotificationItemDto.java`
  - `domain/notification/dto/UnreadCountResponse.java`
  - `domain/course/dto/MyJoinRequestItemDto.java`
- 변경:
  - `domain/course/repository/CourseJoinRequestRepository.java` — `findByStudentIdWithCourse` 추가
  - `domain/course/service/CourseJoinRequestService.java` — `getMyJoinRequests` + approve/reject/block 알림 발행 wiring
  - `domain/course/controller/CourseJoinRequestController.java` — `GET /api/courses/join-requests/me` 추가
  - `FRONTEND_V2_V3_API.md` — 학생 상태 조회 / 알림 API 섹션 추가

---

## [2026-05-01] 보안·가입·세션 안정화 — learning 권한 게이트 + join 우회 차단 + PENDING 동시성 + signup 검증

### 배경

`feat/v3-springboot` 의 기능은 거의 완성됐지만 다음 5건의 보안/정합성 구멍이 남아있었다.

1. **learning 세션 API 권한 게이트 누락** — `LearningSessionController` 의 두 엔드포인트는 `@PreAuthorize("hasAuthority('STUDENT') or hasAuthority('TEACHER')")` 만 검사. 학생이 자기가 수강하지 않은 강의실의 `lectureId` 로도 세션을 만들 수 있었다.
2. **deprecated `/api/courses/join?code=` 가 즉시 Enrollment 생성** — 승인형 가입(`CourseJoinRequest`) 흐름을 우회하는 역방향 경로.
3. **`CourseJoinRequest` 동시성 미방어** — exists+save 구조라 동시 요청 시 PENDING 중복 가능.
4. **회원가입 입력 검증 부재** — `AuthService.signup()` 이 role 별 필수 필드를 검사하지 않고 `"반 미지정"` / `"학교 미지정"` 같은 더미 문자열을 강제 주입.
5. **테스트 부재** — `course/`, `user/`, `notification/`, `learning/service` 테스트 디렉토리 자체가 없었다.

### 변경

**1) learning 세션 권한 게이트**
- `LearningSessionService.validateLectureAccess(Long lectureId)` 패키지-private 메서드 신설.
- `LectureRepository.findByIdWithCourse` (기존 JOIN FETCH 메서드) + `EnrollmentRepository.existsByStudentAndCourse` 로 권한 검증.
- TEACHER 는 강의가 속한 course 의 소유 교사여야 함, STUDENT 는 활성 Enrollment 가 있어야 함. 둘 다 아니면 `BusinessException(FORBIDDEN)`.
- `getOrCreateSession()` / `streamSessionEvent()` 진입부에서 호출.
- 이벤트 API 의 `lectureId` 쿼리 파라미터를 `required = true` 로 변경 (권한 검증 + FastAPI `EventRequest.lecture_id` 양쪽에 필요).

**2) deprecated `/api/courses/join` 위임 전환**
- `CourseController.joinCourse(...)` 가 `enrollmentService.enrollCourseByCode()` 대신 `joinRequestService.createJoinRequest(new CourseJoinRequestCreateDto(invitationCode))` 호출.
- 응답 메시지: `"가입 요청이 접수되었습니다."`. 이미 등록 / BLOCKED / PENDING 분기는 `CourseJoinRequestService` 의 기존 분기와 동일.
- `CourseJoinRequestCreateDto` 에 `@JsonCreator` 생성자 추가 — Jackson 역직렬화 + 호환 컨트롤러에서의 직접 생성 양쪽 지원.
- `EnrollmentService.enrollCourseByCode()` 는 호출처가 사라졌지만 즉시 삭제하지 않고 다음 라운드에 정리.

**3) CourseJoinRequest 동시성 방어**
- `CourseJoinRequestService.createJoinRequest` 를 **lock → transaction → 재검증 → 저장** 구조로 재구성.
- `DistributedLockService.executeWithLock("course-join-request:{studentId}:{courseId}", 3, 5, ...)` 로 (student, course) 단위 직렬화.
- 락 안에서 `TransactionTemplate.execute(...)` 로 트랜잭션 commit 까지 끝내기 — 단순 `@Transactional` 만 쓰면 commit 전에 락이 풀려 중복 PENDING 이 새는 시나리오 차단.
- 락 안 재검증: Enrollment / BLOCKED / PENDING 순.
- `TransactionTemplate` 빈 등록을 위해 `config/TransactionConfig.java` 신설.

**4) Signup 입력 검증**
- `SignUpRequestDto` 에 클래스 레벨 `@AssertTrue` 메서드 2개:
  - `isStudentFieldsValid()` — STUDENT 면 `grade != null && classNumber 비어있지 않음`
  - `isTeacherFieldsValid()` — TEACHER 면 `schoolName`/`department` 둘 다 채워져 있음
- `AuthService.signup()` 의 `?:` 더미 폴백 (`grade ?: 0`, `?: "반 미지정"` 등) 전부 삭제. 검증 통과한 값을 그대로 저장.

**5) 테스트**
- `LearningSessionAuthorizationTest` — 교사 소유/타교사/수강 학생/비수강 학생/존재하지 않는 강의 5케이스
- `CourseJoinRequestServiceTest` — 최초 생성 / Enrollment 중복 / BLOCKED / PENDING 중복 / REJECTED 후 재요청 허용 / 승인·거절·차단 알림. 락+트랜잭션은 inline 실행하도록 stub
- `NotificationServiceTest` — save / unread count / markAsRead (정상·404) / markAllAsRead / streamRegistry push
- `CourseControllerJoinDelegationTest` — deprecated `/join` 이 `CourseJoinRequestService` 로 위임되는지

### 설계 결정

| 결정 | 사유 |
|---|---|
| 권한 검증을 `LearningSessionService` 내부 메서드로 두고 `LectureService` 호출 안 함 | `LectureService` 는 의존성이 8+개로 무거움. 단순 검증을 위해 cross-domain 강결합 추가는 과함. 로직 자체는 10줄. |
| 락 → 트랜잭션 (역순 X) | 트랜잭션이 락보다 먼저 시작되면 commit 전에 락이 풀려 동시 요청이 직전 commit 을 보지 못함. `TransactionTemplate` 으로 commit 까지 락 안에서 완료. |
| `EnrollmentService.enrollCourseByCode()` 즉시 삭제 안 함 | deprecated 경로가 위임으로 전환됐으므로 호출처가 사라졌지만, 안전을 위해 후속 PR 에서 grep 으로 호출처 0 확인 후 제거 |
| `lectureId` 이벤트 API 필수화 | 기존 `required=false` 였으나 권한 검증의 단일 진입점이 필요. FE 가 이미 보내고 있다는 사용자 명시. |
| DB 의 `"반 미지정"` 같은 더미 데이터 마이그레이션 안 함 | 이번 라운드는 신규 가입 입력 차단까지. 기존 데이터 정리는 별도 데이터 작업 |

### 보류 (다음 라운드)

- `EnrollmentService.enrollCourseByCode()` 완전 삭제
- 멀티 인스턴스 SSE 알림 브로드캐스트 (저장형 알림이 복구 수단)
- v1 legacy AI 흐름의 권한 게이트 (이번 라운드는 v3 learning 만)
- 기존 DB 의 더미 문자열 마이그레이션

### 관련 파일

- 신규:
  - `config/TransactionConfig.java`
  - `src/test/.../domain/learning/service/LearningSessionAuthorizationTest.java`
  - `src/test/.../domain/course/service/CourseJoinRequestServiceTest.java`
  - `src/test/.../domain/notification/service/NotificationServiceTest.java`
  - `src/test/.../domain/course/controller/CourseControllerJoinDelegationTest.java`
- 변경:
  - `domain/learning/service/LearningSessionService.java` — `validateLectureAccess` + 진입부 호출 + `lectureId` 필수화
  - `domain/learning/controller/LearningSessionController.java` — 이벤트 API `lectureId` `required = true`
  - `domain/course/controller/CourseController.java` — deprecated `/join` 을 `CourseJoinRequestService` 로 위임
  - `domain/course/service/CourseJoinRequestService.java` — lock → transaction → 재검증 구조로 재구성
  - `domain/course/dto/CourseJoinRequestCreateDto.java` — `@JsonCreator` 생성자 추가
  - `domain/user/dto/SignUpRequestDto.java` — role 별 `@AssertTrue` 검증
  - `domain/user/service/AuthService.java` — 더미 폴백 제거
  - 도메인 README 4건 (user/course/security/learning) — refresh 회전·OAuth one-time code·deprecated join·learning 게이트 정정

---

## [2026-05-06] 강의실 정보 수정 500 — `description` 컬럼 길이 초과 + DB 제약 예외 미처리

### 증상

`PUT /api/courses/{courseId}` 로 긴/여러 줄/특수문자 포함 설명을 저장하려 하면 500 응답.
운영 로그(예상):

```
org.springframework.dao.DataIntegrityViolationException
  → root cause: com.mysql.cj.jdbc.exceptions.MysqlDataTruncation:
    Data truncation: Data too long for column 'description' at row 1
```

500이 떨어지는 이유는 두 가지가 동시에 작용:

1. 운영 DB의 `courses.description` 컬럼이 과거 `VARCHAR(255)` 등 짧은 타입으로 남아있음
   (`@Lob` + `ddl-auto:update` 조합은 신규 테이블에만 LONGTEXT를 적용하고 기존 컬럼 타입은 변경하지 않음)
2. `GlobalExceptionHandler` 가 `DataIntegrityViolationException` 을 별도 처리하지 않아
   catch-all(Exception) 로 떨어져 500 으로 변환됨

### 원인 분석

- `Course` 엔티티는 `@Lob @Column private String description;` — Hibernate는 신규 생성 시 LONGTEXT로 만들지만 prod 의 기존 컬럼은 그대로 둔다.
- `CourseUpdateRequestDto` 에 길이 제약이 없어 컨트롤러 단에서 거대 문자열을 막지 못한다 (`@NotBlank` 만 있음).
- `GlobalExceptionHandler` 의 마지막 catch-all 핸들러가 `INTERNAL_SERVER_ERROR(5000)` 으로 매핑하므로
  DB 길이 초과 같은 명백한 클라이언트 입력 오류도 5xx 로 응답된다.

### 수정 방법

1. **DTO validation 보강**
   - `CourseCreateRequestDto`, `CourseUpdateRequestDto`
     - `title` : `@NotBlank` + `@Size(max = 255)`
     - `description` : `@NotBlank` + `@Size(max = 20000)`
   - 줄바꿈/이모지/특수문자는 그대로 허용 (별도 치환·필터링 없음).
2. **Course 엔티티 컬럼 정의 명시**
   - `@Lob @Column` → `@Column(columnDefinition = "LONGTEXT")` 로 교체.
   - 신규 환경에는 자동으로 LONGTEXT 적용. 기존 운영 DB는 별도 `ALTER TABLE` 필요 (아래 절차 참고).
3. **GlobalExceptionHandler 보강**
   - `@ExceptionHandler(DataIntegrityViolationException.class)` 추가.
   - root cause 가 Hibernate `DataException` 또는 메시지에 `Data too long`/`Data truncation` 포함 시
     → "입력값이 허용된 길이를 초과했습니다." (HTTP 400, code `4000`)
   - 그 외 무결성 위반 → "요청 데이터가 제약 조건을 위반했습니다." (HTTP 400, code `4000`)
   - root cause 는 `log.warn` 으로만 남기고 응답 본문엔 노출하지 않음.

### 운영 DB 보정 절차 (수동)

ddl-auto:update 만으로는 기존 컬럼 타입이 LONGTEXT로 바뀌지 않으므로 배포 후 한 번 실행:

```sql
-- 1) 현재 상태 확인
SHOW FULL COLUMNS FROM courses;

-- 2) description 을 LONGTEXT로 (NULL 허용 그대로)
ALTER TABLE courses MODIFY description LONGTEXT NULL;

-- 3) title 길이가 255 미만이면 명시적으로 맞춰둠
ALTER TABLE courses MODIFY title VARCHAR(255) NOT NULL;

-- 4) 검증
SHOW FULL COLUMNS FROM courses;
```

확인 명령:

```bash
docker exec -it dev-mysql mysql -u root -p uoucapstone -e "SHOW FULL COLUMNS FROM courses;"
```

### 관련 파일

- 변경
  - `domain/course/dto/CourseCreateRequestDto.java` — `@Size` 추가
  - `domain/course/dto/CourseUpdateRequestDto.java` — `@Size` 추가
  - `domain/course/entity/Course.java` — `@Lob` → `columnDefinition = "LONGTEXT"`, `title` `length = 255` 명시
  - `config/GlobalExceptionHandler.java` — `DataIntegrityViolationException` 핸들러 추가
- 신규 테스트
  - `src/test/.../domain/course/dto/CourseRequestDtoValidationTest.java`
  - `src/test/.../config/GlobalExceptionHandlerDataIntegrityTest.java`

---

## [2026-05-08] Flyway 도입 — 스키마 마이그레이션 버전 관리 + ddl-auto validate 전환

### 배경

운영/개발 DB 가 살아있는 상태에서 `ddl-auto: update`(prod) / `create`(local) 로 스키마를 관리하고 있었다. 두 가지 한계가 있었다:

1. 어떤 DDL 이 언제 적용됐는지 코드 어디에도 기록이 남지 않음 → 롤백·감사 불가.
2. 컬럼 rename, NOT NULL 추가, 인덱스 변경, 백필 같은 작업은 `update` 가 처리 못 하거나 위험하게 처리. 실제로 [2026-05-06] `courses.description` 사례에서 `@Lob + ddl-auto:update` 가 기존 컬럼 타입을 바꾸지 않아 수동 `ALTER TABLE` 보정이 필요했고, [2026-04-16] 의 `3-4 ddl-auto` 항목으로 미뤄두었던 처리도 같은 맥락.

### 조치

#### 1. 의존성

```gradle
// uou-capstone/build.gradle
implementation 'org.flywaydb:flyway-core'
implementation 'org.flywaydb:flyway-mysql'   // Flyway 10+ 부터 MySQL 별도 모듈 (누락 시 prod 부팅 실패)
```

Spring Boot 3.4 BOM 이 버전 관리하므로 명시 불필요.

#### 2. 프로필별 yml 변경

| 파일 | 변경 |
|---|---|
| `application-prod.yml` | `ddl-auto: update → validate`, `spring.flyway.enabled=true`, `baseline-on-migrate=true`, `baseline-version=1`, `validate-on-migrate=true` |
| `application-local.yml` | `ddl-auto: create → validate`, 동일 Flyway 설정 |
| `application-test.yml` | `spring.flyway.enabled=false` (H2 MODE=MySQL 호환 회피, Hibernate `create-drop` 유지) |

#### 3. V1 baseline

- 위치: `uou-capstone/src/main/resources/db/migration/V1__baseline_schema.sql`
- 출처: develop EC2 의 `dev-mysql` 컨테이너 `mysqldump --no-data` 결과
- 정리: `AUTO_INCREMENT=<숫자>` 절 제거 (환경별 노이즈)
- 매칭: 도메인 `@Entity` 20개 ↔ DB 테이블 20개 1:1 확인 (BaseTimeEntity 는 `@MappedSuperclass`)

### 동작 방식

- **기존 develop/prod DB**: `baseline-on-migrate=true` + `baseline-version=1` 에 의해 V1 을 `type=BASELINE` 으로 기록만 하고 실행 skip. → 운영 DB 변경 없음.
- **빈 DB (신규 환경, 새 로컬 DB)**: V1 이 실제 실행되어 모든 테이블 생성. `ddl-auto: validate` 가 entity ↔ DB mismatch 검사 통과해야 부팅.
- **테스트 (H2 MODE=MySQL)**: Flyway off, Hibernate `create-drop` 만으로 스키마 생성. MySQL 전용 SQL(JSON 함수, `ENGINE=InnoDB`, `utf8mb4_0900_ai_ci`) 호환 이슈 회피.

### 검증 결과

- 로컬: `./gradlew test` 통과 (Flyway 비활성화로 H2 흐름 그대로).
- V1 SQL syntax: dev-mysql 의 빈 DB(`flyway_v1_test`) 에 dump 실행 → 20 테이블 정상 생성.
- develop 배포 후 부팅 로그:

  ```
  Database: jdbc:mysql://dev-mysql:3306/uoucapstone (MySQL 8.0)
  Schema history table `uoucapstone`.`flyway_schema_history` does not exist yet
  Creating Schema History table `uoucapstone`.`flyway_schema_history` with baseline ...
  Successfully baselined schema with version: 1
  Tomcat started on port 8080 (http) with context path '/'
  Started UouCapstoneApplication in 23.417 seconds
  ```

  컨테이너 status: `Up (healthy)`. `ddl-auto=validate` 가 entity ↔ DB mismatch 없이 통과 → develop DB 와 entity 가 일치함을 부팅 자체로 보장.

### 운영 주의사항

- **V1 파일은 develop/prod 첫 배포 후 절대 수정 금지**. Flyway checksum 검증이 깨지면 이후 모든 마이그레이션이 차단됨. `flyway repair` 가 필요해지는데 운영 DB 에 손대는 일은 피해야 한다.
- 향후 스키마 변경(컬럼 추가/rename/NOT NULL 부여/인덱스/백필)은 모두 `V2__add_xxx.sql`, `V3__...sql` 로 추가. 백필도 SQL(`UPDATE ... WHERE ...`)로 작성해 코드 리뷰 대상으로 만든다.
- [2026-05-06] 같은 `@Lob` → `LONGTEXT` 보정도 향후에는 `V2__alter_courses_description.sql` 같은 마이그레이션으로 처리.
- 부팅이 `ddl-auto: validate` 에서 깨지면 (entity ↔ DB 컬럼 mismatch) → PR revert + V1 재점검. V1 은 실행되지 않았으므로 DB 변경 없음.

### 관련 파일

- 변경
  - `uou-capstone/build.gradle` — `flyway-core`, `flyway-mysql` 의존성 추가
  - `uou-capstone/src/main/resources/application-prod.yml` — ddl-auto + flyway 설정
  - `uou-capstone/src/main/resources/application-local.yml` — 동일
  - `uou-capstone/src/test/resources/application-test.yml` — `spring.flyway.enabled=false`
- 신규
  - `uou-capstone/src/main/resources/db/migration/V1__baseline_schema.sql` — develop DB 기반 baseline (20 테이블)
- PR
  - `feat/flyway-baseline → feat/v3-springboot` (2026-05-08, commit `5f0b702`)


---

## [2026-05-31] 강의학습 에이전트 채팅 저장

### 증상

FE에서 강의학습 화면의 사용자-에이전트 채팅을 새로고침/재진입 후 복원할 수 있는 저장 기능을 요구했다. 기존 v3 학습 세션은 Spring이 FastAPI 세션/SSE를 프록시하는 구조라 Spring DB에는 채팅 세션이나 메시지가 남지 않았다.

### 원인

통합 학습 세션 상태는 FastAPI Redis 세션에 임시로 유지되고, Spring `LearningSessionService`는 `USER_MESSAGE` 요청과 `agent_delta` 응답을 저장하지 않고 그대로 전달만 했다. FastAPI의 `SAVE_AND_EXIT`도 Spring 영속 저장과 연결되어 있지 않아 FE가 신뢰할 장기 조회 API가 없었다.

### 조치

Spring DB에 `learning_chat_sessions` / `learning_chat_messages`를 추가하고, 강의학습 세션 생성 시 채팅 세션을 생성하도록 연결했다. `USER_MESSAGE`는 사용자 메시지로 저장하고, SSE 응답 중 `agent_delta.channel="main"`만 모아 `done` 완료 시 에이전트 메시지로 저장한다. FE 조회용으로 강의별 채팅 세션 목록과 세션별 메시지 조회 API도 추가했다.

### 관련 파일

- `uou-capstone/src/main/java/io/github/uou_capstone/aiplatform/domain/learning/controller/LearningSessionController.java`
- `uou-capstone/src/main/java/io/github/uou_capstone/aiplatform/domain/learning/service/LearningSessionService.java`
- `uou-capstone/src/main/java/io/github/uou_capstone/aiplatform/domain/learning/service/LearningChatPersistenceService.java`
- `uou-capstone/src/main/resources/db/migration/V6__learning_chat_history.sql`
- `uou-capstone/FRONTEND_V2_V3_API.md`

---
## [2026-05-20] 출석 세션 시간 스키마를 문자열 계약으로 고정

### 배경

FE에서 `POST /api/courses/{courseId}/attendance/sessions` 호출 시 `Content-Type: application/json` 상태에서 `startTime` / `endTime`을 Swagger가 제안한 객체 형태(`{ "hour": 10, "minute": 0, "second": 0, "nano": 0 }`)로 보내면 400이 발생한다고 제보했다.

BE DTO 필드는 `LocalTime`이므로 Jackson은 ISO 계열 문자열 값을 기대한다. 객체 형태의 시간 값은 컨트롤러/서비스 계층에 진입하기 전 request body 역직렬화 단계에서 실패하고, `GlobalExceptionHandler`에 의해 일반 JSON 파싱 오류 응답으로 매핑된다.

### 결정

공개 API 계약은 문자열 시간 형식으로 유지한다.

```json
{
  "startTime": "10:00:00",
  "endTime": "12:00:00"
}
```

기존 FE 인수인계 문서와 동일한 계약이며, 두 번째 요청 형태를 추가하지 않는다.

### 변경

- 출석 세션 요청/응답 시간 필드에 `@JsonFormat(shape = STRING, pattern = "HH:mm:ss")`를 명시했다.
- Swagger UI가 `{hour, minute, second, nano}` 객체 형태를 제안하지 않도록 `@Schema(type = "string", format = "time", example = "...")`를 명시했다.
- `AttendanceSessionTimeFormatTest`를 추가해 문자열 입력은 성공하고, 출력은 `HH:mm:ss`로 유지되며, 객체 형태 시간 입력은 거부되는지 검증했다.

---

## [2026-05-21] 강의실 createdAt 응답 계약 안정화

### 배경

FE에서 강의실 생성/상세 응답에 생성 일자가 안정적으로 포함되는지, JSON 필드명이 `createdAt`인지 `created_at`인지 확인을 요청했다.

`POST /api/courses`와 `GET /api/courses/{courseId}`는 모두 `CourseResponseDto`를 반환하며, DTO에는 이미 `createdAt` 필드가 노출되어 있었다. 다만 `BaseTimeEntity`의 `@CreatedDate` / `@LastModifiedDate`는 Spring Data JPA Auditing에 의존하는데, 애플리케이션 설정에서 JPA Auditing이 활성화되어 있지 않았다. 따라서 응답 필드는 존재하지만 저장 후 값이 `null`일 수 있었다.

### 결정

공개 응답 계약은 camelCase로 고정한다.

```json
{
  "createdAt": "2026-05-20T10:30:15"
}
```

Spring API 응답에서는 `created_at`을 사용하지 않는다.

### 변경

- `@EnableJpaAuditing`을 가진 `JpaAuditingConfig`를 추가해 `Course.createdAt` / `updatedAt`이 persist 시점에 채워지도록 했다.
- `CourseResponseDtoTest`를 확장해 DTO 매핑과 JSON 직렬화 필드명이 `created_at`이 아니라 `createdAt`인지 검증했다.
- `CourseAuditingTest`를 추가해 저장된 `Course` row의 audit timestamp가 null이 아닌지 검증했다.

### 검증

```powershell
.\gradlew.bat test --tests io.github.uou_capstone.aiplatform.domain.course.dto.CourseResponseDtoTest --tests io.github.uou_capstone.aiplatform.domain.course.repository.CourseAuditingTest --no-daemon
.\gradlew.bat test --tests io.github.uou_capstone.UouCapstoneApplicationTests --no-daemon
```

---

## [2026-05-22] FE 확인 항목: 시험 노출/알림 시간/수강 등록시간 계약 정리

### 배경

FE에서 다음 세 가지 확인을 요청했다.

- 학생 계정의 `GET /api/courses/{courseId}/contents` 응답에 교사가 생성한 시험 세션이 `lectures[].examSessions`로 내려오는지.
- `GET /api/notifications`와 `/api/notifications/stream` 알림 항목에 화면 표시 가능한 `createdAt` 시간이 포함되는지.
- `GET /api/courses/{courseId}/students` 응답에서 보장되는 등록시간 필드가 무엇인지.

### 결정

- 시험 세션 목록은 별도 공개/비공개 필드를 추가하지 않고, 강의실 참가자 권한(담당 교사 또는 ACTIVE 수강생)으로 조회 가능하게 한다. 학생이 실제 시험 상세를 열 수 있는 조건은 기존처럼 `status=READY`다.
- 알림 시간은 REST/SSE 모두 `createdAt` camelCase, ISO offset date-time UTC 형식으로 고정한다.
- 학생 목록 등록시간 공식 필드는 `enrolledAt` camelCase, ISO offset date-time UTC 형식으로 고정한다.

### 변경

- `CourseService.getCourseContents` 권한 검사를 `CourseAccessService.loadCourseAsParticipant`로 통일해 학생은 ACTIVE 수강생일 때만 시험 세션 목록을 받도록 했다.
- `NotificationItemDto.createdAt`을 `OffsetDateTime` UTC로 변경하고, SSE payload도 같은 값을 사용하도록 했다. 감사 시간이 아직 비어 있는 객체에서도 null이 내려가지 않도록 fallback을 둔다.
- `CourseStudentItemDto.enrolledAt`에 Swagger `date-time` 스키마를 명시해 FE 계약을 문서화했다.
- 테스트 추가/보강:
  - `CourseServiceContentsTest`
  - `NotificationItemDtoTest`
  - `CourseStudentItemDtoTest`
  - `NotificationServiceTest`의 stream push DTO `createdAt` 검증

### 검증

```powershell
.\gradlew.bat test --tests io.github.uou_capstone.aiplatform.domain.course.service.CourseServiceContentsTest --tests io.github.uou_capstone.aiplatform.domain.notification.dto.NotificationItemDtoTest --tests io.github.uou_capstone.aiplatform.domain.course.dto.CourseStudentItemDtoTest --tests io.github.uou_capstone.aiplatform.domain.notification.service.NotificationServiceTest --no-daemon
.\gradlew.bat test --no-daemon
```

---

## [2026-05-31] 강의실 종합분석 내부 Pageable 제한 분리

### 증상

FE는 Swagger 명세에 맞춰 `POST /api/courses/{courseId}/reports/classroom/analyze`와 `POST /api/courses/{courseId}/reports/classroom/analyze/stream`을 body 없이 호출했다. 동기 분석은 `size 는 100 이하이어야 합니다.` 오류를 반환했고, 스트리밍 분석은 FastAPI 호출 전에 payload 생성 단계에서 실패해 SSE 요청이 500으로 종료될 수 있었다.

### 원인

분석 API 자체는 path parameter만 받지만, BE 내부 `ClassroomReportService.preparePayload`가 학생 리포트 목록을 재사용하면서 `PageRequest.of(0, 1000)`을 전달했다. 이 호출이 외부 학생 목록 API와 동일한 `CourseStudentReportService.getStudentReportList` 경로를 타면서 `PageableSupport.validate`의 클라이언트 요청용 `size <= 100` 검증에 걸렸다.

### 조치

- 외부 학생 리포트 목록 API는 기존 `size <= 100` 검증을 유지했다.
- 강의실 종합분석 payload 수집 전용 `getStudentReportListForClassroomAnalysis`를 추가해 내부 `PageRequest.of(0, 1000)`은 클라이언트 pageable 검증을 타지 않도록 분리했다.
- 두 경로가 동일한 집계 로직을 쓰도록 내부 공통 메서드로 학생 리포트 목록 생성 로직을 모았다.

### 검증

```powershell
.\gradlew.bat test --tests io.github.uou_capstone.aiplatform.domain.course.report.service.CourseStudentReportServiceAiContextTest --no-daemon
```

### 관련 파일

- `src/main/java/io/github/uou_capstone/aiplatform/domain/course/report/classroom/service/ClassroomReportService.java`
- `src/main/java/io/github/uou_capstone/aiplatform/domain/course/report/service/CourseStudentReportService.java`
- `src/test/java/io/github/uou_capstone/aiplatform/domain/course/report/service/CourseStudentReportServiceAiContextTest.java`

---

## [2026-05-31] 리포트 화면 FE 연동 데이터 보강

### 증상

리포트 페이지 개편 후 FE 화면은 기존 학생 리포트 API만으로 기본 렌더링은 가능했지만, 상단 기준 적용 현황, 학생 활동 지표, 강의실 학습 흐름, 학생 리포트 챗봇 이전 대화 복원 영역은 임시값 또는 FE 가공값에 의존해야 했다.

### 원인

기존 BE 리포트 API는 학생 목록/상세 리포트와 강의실 AI 리포트 저장 조회 중심이었다. 기준 요약, 학생별 활동 요약, 강의별 flow 집계, report-chat 전용 history 저장/조회 계약이 분리되어 있지 않았고, 학생 상세 응답도 headline/bullet/코칭 인사이트처럼 FE 본문 카드가 직접 쓰는 필드를 별도로 노출하지 않았다.

### 조치

- `GET /api/courses/{courseId}/reports/criteria/summary`를 추가해 기본 항목 수, 추가 기준 수, 적용 상태, 기준 반영 시각을 반환한다.
- `GET /api/courses/{courseId}/reports/students/{studentId}` 응답에 종합 점수, headline, summary bullets, 강점/보완/코칭/추천 액션, 생성/수정 시각 필드를 추가했다.
- `GET /api/courses/{courseId}/reports/students/{studentId}/activity-summary`와 `GET /api/courses/{courseId}/reports/classroom/flow`를 추가해 기존 저장 데이터 기반 활동/flow 집계를 제공한다.
- report-chat 전용 세션/메시지 테이블과 `GET /api/courses/{courseId}/reports/students/{studentId}/chat/history`를 추가하고, 기존 `chat/stream`은 optional `sessionId`를 받아 user/assistant 메시지를 저장하도록 보강했다.

### 검증

```powershell
.\gradlew.bat test --tests io.github.uou_capstone.aiplatform.domain.course.report.studentchat.service.StudentReportChatServiceTest --tests io.github.uou_capstone.aiplatform.domain.course.report.service.CourseStudentReportServiceAiContextTest
.\gradlew.bat test --no-daemon
```

### 관련 파일

- `src/main/java/io/github/uou_capstone/aiplatform/domain/course/report/controller/CourseReportController.java`
- `src/main/java/io/github/uou_capstone/aiplatform/domain/course/report/criteria/controller/CourseReportCriteriaController.java`
- `src/main/java/io/github/uou_capstone/aiplatform/domain/course/report/service/CourseReportSupplementService.java`
- `src/main/java/io/github/uou_capstone/aiplatform/domain/course/report/studentchat/service/StudentReportChatService.java`
- `src/main/resources/db/migration/V7__student_report_chat_history.sql`

---

## [2026-06-01] 리포트 API FE 계약 값 안정화

### 증상

리포트 페이지 FE 연동 확인 과정에서 `criteriaStatus`와 `classroom/flow.riskLevel`이 임의 문자열처럼 보였고, `overallScorePercent` 산식과 `chat/history` 응답 규모 제어 방식이 API 계약에 명확히 고정되어 있지 않았다. FE는 배지/문구/복원 UI를 안정적으로 매핑하기 위해 값 목록과 정렬/페이지네이션 규칙이 필요했다.

### 원인

초기 보강 구현은 현재 화면에 필요한 데이터를 먼저 제공하는 데 집중해 `criteriaStatus`를 `DEFAULT/APPLIED`, `riskLevel`을 `NORMAL/WATCH`로 반환했다. 또한 학생 리포트 챗봇 기록은 전체 배열로 내려주고 있었고, 점수 필드는 기존 집계 로직을 재사용했지만 FE 문서에는 산식이 따로 적혀 있지 않았다.

### 조치

- `criteriaStatus` 고정 값 목록을 `NONE`, `ACTIVE`, `STALE`, `REFLECTING`으로 정리하고 현재 구현은 기준 없음 `NONE`, 기준 있음 `ACTIVE`를 반환하도록 변경했다.
- `classroom/flow.riskLevel` 고정 값 목록을 `LOW`, `MEDIUM`, `HIGH`, `INSUFFICIENT_DATA`로 정리하고 `riskReasons`는 문자열 배열로 유지했다.
- `chat/history`를 `PageResponse`로 변경하고 `page`, `size`, optional `sessionId` 필터를 지원하도록 했다. 기본 정렬은 FE 복원에 맞게 `createdAt ASC`, `id ASC`로 고정했다.
- `overallScorePercent` 산식을 FE 문서에 명시했다. 현재 산식은 강의 내 유효한 시험 결과별 `totalScore / maxScore * 100`의 산술 평균이며, 제출률/질문/참여도/역량 점수는 아직 가중치에 포함하지 않는다.

### 검증

```powershell
.\gradlew.bat test --tests io.github.uou_capstone.aiplatform.domain.course.report.studentchat.service.StudentReportChatServiceTest --tests io.github.uou_capstone.aiplatform.domain.course.report.service.CourseStudentReportServiceAiContextTest --no-daemon
.\gradlew.bat test --no-daemon
```

### 관련 파일

- `src/main/java/io/github/uou_capstone/aiplatform/domain/course/report/criteria/dto/CriteriaStatus.java`
- `src/main/java/io/github/uou_capstone/aiplatform/domain/course/report/dto/ClassroomFlowRiskLevel.java`
- `src/main/java/io/github/uou_capstone/aiplatform/domain/course/report/controller/CourseReportController.java`
- `src/main/java/io/github/uou_capstone/aiplatform/domain/course/report/studentchat/service/StudentReportChatPersistenceService.java`
- `FRONTEND_V2_V3_API.md`

---

## [2026-06-01] 회원가입 rate limit 완화

### 증상

배포 서버에서 테스트 학생 계정을 연속 생성할 때 `/api/auth/signup` 요청이 5회 이후 `429 Too Many Requests`로 차단되었다. 수강 신청 테스트 데이터처럼 여러 학생 계정을 한 번에 준비해야 하는 경우 1분 단위로 작업이 끊겼다.

### 원인

`AuthRateLimitInterceptor`가 인증 API별 IP 기반 제한을 적용하며, `/api/auth/signup`만 분당 5회로 설정되어 있었다. 테스트/시연 데이터 생성에는 낮은 값이었다.

### 조치

회원가입 요청 제한을 IP 기준 분당 20회로 완화했다. 로그인, refresh, 이메일 중복 확인 제한은 기존 값을 유지했다.

### 검증

```powershell
.\gradlew test --no-daemon
```

### 관련 파일

- `uou-capstone/src/main/java/io/github/uou_capstone/aiplatform/config/AuthRateLimitInterceptor.java`

---

## [2026-06-01] 통합학습 재입장 시 채팅 히스토리 복원

### 증상

통합학습 중 브라우저 뒤로가기나 라우트 이동 후 같은 강의에 다시 들어가면, 이전에 에이전트와 학습했던 메시지가 사라지고 FE 화면에 `메시지가 없습니다` 빈 상태가 표시됐다.

### 원인

`POST /api/learning/sessions/{lectureId}`가 `sessionId` 없이 호출될 때마다 Spring 채팅 세션을 새로 생성했다. 기존 메시지는 DB에 저장돼 있었지만, 재입장 응답의 `chatSessionId`가 새 빈 세션을 가리켜 FE가 `/messages`를 조회해도 이전 대화가 복원되지 않았다.

### 조치

- `sessionId`가 명시되지 않은 일반 강의 진입 요청에서는 같은 사용자 + 같은 강의의 종료되지 않은 최신 `LearningChatSession`을 먼저 재사용하도록 변경했다.
- 기존 active 세션이 없을 때만 새 채팅 세션을 생성한다.
- FE가 `chatSessionId`로 메시지 히스토리를 조회하고, 의도적 종료가 아닌 뒤로가기에서는 `SAVE_AND_EXIT`를 보내지 않도록 handoff 가이드를 추가했다.

### 검증
```powershell
.\gradlew.bat test --tests io.github.uou_capstone.aiplatform.domain.learning.service.LearningSessionAuthorizationTest
```

### 관련 파일

- `src/main/java/io/github/uou_capstone/aiplatform/domain/learning/repository/LearningChatSessionRepository.java`
- `src/main/java/io/github/uou_capstone/aiplatform/domain/learning/service/LearningChatPersistenceService.java`
- `src/main/java/io/github/uou_capstone/aiplatform/domain/learning/service/LearningSessionService.java`
- `src/test/java/io/github/uou_capstone/aiplatform/domain/learning/service/LearningSessionAuthorizationTest.java`
- `docs/handoff/LEARNING_CHAT_RESTORE_FE.md`
