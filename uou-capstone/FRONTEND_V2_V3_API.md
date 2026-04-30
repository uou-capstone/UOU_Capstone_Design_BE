# 프론트엔드 API 가이드 (FE ↔ Spring)

이 문서는 **프론트엔드가 호출해야 하는 Spring API만** 정리한다.  
내부 AI 서비스(FastAPI) 연동 경로는 문서화하지 않는다.

**최종 갱신 (2026-04-10):** v3 통합학습을 `llm_multi_agent` Bridge·Session 계약에 맞춰 정리 (`USER_MESSAGE` → `question`, 페이지 쿼리, `done.data`·SSE 파싱, 퀴즈 저장 위치). v1 `stream/next` SSE를 `thought` / `message` 이벤트 규격으로 반영.

---

## 1) 공통 규칙

- 베이스 URL: Spring 서버 주소 사용 (`http://{host}:{port}`)
- 인증: 대부분 `Authorization: Bearer <accessToken>`
- 권한: `TEACHER`, `STUDENT` (서버 `@PreAuthorize` 기준)
- 에러 처리: 4xx/5xx 응답의 `message` 또는 `detail`을 사용자 메시지로 매핑
- 네이밍: 요청 JSON은 기본 camelCase, 일부 필드는 snake_case도 허용(백엔드 alias 처리)

---

## 2) 인증 API

### 로그인
- `POST /api/auth/login`
- 요청: 이메일/비밀번호
- 응답: access token, refresh token 등

### 토큰 갱신
- `POST /api/auth/refresh`

### 로그아웃
- `POST /api/auth/logout`
- 헤더: Bearer 토큰 필요

---

## 3) v3 학습 세션 (통합 학습 UX)

연동 계약은 **FastAPI `llm_multi_agent` 브랜치의 Bridge·Session API 문서**와 동일한 의미를 갖도록 맞춘다.  
프론트는 **Spring만** 호출한다.

### 세션 조회/생성
- `POST /api/learning/sessions/{lectureId}`
- 권한: `STUDENT` 또는 `TEACHER`
- 쿼리(선택):
  - `pdfPath`: 세션 초기 연결 시 사용할 PDF 경로 (생략 시 서버가 강의의 최신 PDF 경로를 조회해 전달할 수 있음)
  - `sessionId`: 기존 세션 재사용 ID
- 응답 예시 필드:
  - `session_id`, `lecture_id`, `current_page`, `ai_status_connected`, `created_at`, `updated_at`

### 이벤트 전송 + SSE 스트리밍 (주 진입점)
- `POST /api/learning/sessions/{sessionId}/event`
- 권한: `STUDENT` 또는 `TEACHER`
- Content-Type: `application/json`
- Accept: `text/event-stream`
- 쿼리(선택):
  - `lectureId`: 이벤트 바디에 `lecture_id`로 전달됨 (신규 세션 직후 등)
  - `page` · `pageNumber` · `currentPage`: PDF 뷰어 **현재 페이지(1-based)**. 지정 시 Spring이 `payload`에 `current_page`, `page`, `pageNumber`를 주입해 AI가 잘못된 페이지를 설명하는 것을 방지

요청 바디:
- 필수: `type` (아래 `AppEventType`)
- 이벤트별 필드는 **계약 문서 기준으로 `payload` 안에 둔다** (권장).

#### Spring → FastAPI 변환 요약
- Spring은 최종적으로 FastAPI에 `{ "type", "lecture_id"?, "payload": { ... } }` 형태로 넘긴다.
- 본문에 `payload` 객체를 한 겹 더 씌워 보낸 경우, 서버가 **이중 래핑을 풀어** 한 번만 `payload`로 전달한다.
- `USER_MESSAGE`에서 **`question`이 비어 있고 `text`만 있으면** Spring이 **`question`으로 복사**한다 (구버전 호환). 신규 코드는 **`question`만 사용**할 것.

#### `USER_MESSAGE` (자유 질문) — 표준
```json
{
  "type": "USER_MESSAGE",
  "question": "소프트웨어 프로세스의 4가지 활동은?"
}
```
또는 명시적 `payload`:
```json
{
  "type": "USER_MESSAGE",
  "payload": {
    "question": "이 페이지 핵심만 정리해줘"
  }
}
```

#### 기타 이벤트 `payload` 예시 (타입별)

| `type` | `payload` 예시 | 비고 |
|--------|----------------|------|
| `SESSION_ENTERED` | `{}` | 세션 진입 |
| `START_EXPLANATION_DECISION` | `{ "accept": true }` | 설명 시작 동의 |
| `PAGE_CHANGED` | `{ "page": 2 }` | **페이지는 1-based 권장**; 쿼리 `page`와 함께 쓰면 일관 |
| `USER_MESSAGE` | `{ "question": "..." }` | **`text`는 비권장·호환용** |
| `QUIZ_DECISION` | `{ "accept": true }` | 퀴즈 진행 동의 |
| `QUIZ_TYPE_SELECTED` | `{ "quizType": "OX_Problem" }` | camelCase `quizType` (예: `Five_Choice`, `OX_Problem`, `Flash_Card`, `Short_Answer`) |
| `QUIZ_SUBMITTED` | `{ "quizType": "Five_Choice", "answers": [...] }` | 답안 구조는 AI·문서 계약 따름 |
| `REVIEW_DECISION` | `{ "accept": true }` | 복습 동의 |
| `RETEST_DECISION` | `{ "accept": true }` | 재시험 동의 |
| `NEXT_PAGE_DECISION` | `{ "accept": true }` | 다음 페이지 이동 동의 |
| `SAVE_AND_EXIT` | `{}` | 저장 후 종료 |

### SSE 응답 파싱 (v3)
- Spring은 FastAPI NDJSON 한 줄을 **SSE `event: message`** 의 **`data`에 JSON 문자열 한 줄**로 싣는다.
- 클라이언트: **`fetch` + `ReadableStream` + SSE 파서** 사용 (`EventSource`는 Bearer 불가).
- 각 `data` JSON을 파싱한 뒤 `type`으로 분기:
  - `heartbeat`: 무시
  - `agent_delta`: `channel`이 `thought`면 사고 요약 스트림, `main`이면 본문 스트림 (짧은 청크 누적)
  - `done`: `final: true`, 결과는 **`data` 객체** 안 (퀴즈·채점·UI 신호)
  - `error`: 실패 처리; 가능하면 **`message` 외 `code` / `details`** 도 표시 (AI v2.9+ 권장 포맷)

### `done.data`에서 UI·퀴즈 잡기 (계약 필드명 고정)
- **퀴즈 생성 완료**: `data.quiz` (배열), `data.quiz_type` (문자열)  
  → 별도 REST로 “퀴즈 조회”하지 않고, 스트림에서 받은 배열을 state에 보관해 모달/화면에 표시.
- **UI 위젯**: `data.ui`  
  - 예: `data.ui.widget` — `QUIZ_DECISION`, `NEXT_PAGE_DECISION`, `REVIEW_DECISION`, `RETEST_DECISION`  
  - 예: `data.ui.modal` — `QUIZ_TYPE_PICKER`  
  (정확한 값·시점은 FastAPI Bridge·Session 문서 §5.3)

### 데이터 저장 위치 (참고)
- 통합 세션에서 생성된 퀴즈 본문은 **Spring DB 테이블에 자동 저장되지 않는다**. AI 측 세션 스토어(Redis 등)에 세션 상태로 유지되며, **UI는 스트림으로 받은 `done.data`를 신뢰 소스**로 쓴다.
- 강의실 **시험 카드**(`POST /api/exams/generation` 등)는 별도 플로우이며 DB에 세션·문항이 저장된다.

주요 이벤트 타입 (`AppEventType`):
- `SESSION_ENTERED`
- `START_EXPLANATION_DECISION`
- `PAGE_CHANGED`
- `USER_MESSAGE`
- `QUIZ_DECISION`
- `QUIZ_TYPE_SELECTED`
- `QUIZ_SUBMITTED`
- `REVIEW_DECISION`
- `RETEST_DECISION`
- `NEXT_PAGE_DECISION`
- `SAVE_AND_EXIT`

---

## 4) 시험 API (v2 시험 플로우)

### 시험 생성 (동기)
- `POST /api/exams/generation`
- 권한: `TEACHER`
- 요청 바디(`ExamGenerationRequestDto`):
  - 필수: `lectureId`, `examType`
  - 선택: `materialId`, `targetCount`, `topic`, `lectureContent`, `userProfile`, `displayName`
- 응답:
  - `examSessionId`
  - `materialId`
  - 유형별 문제 배열
  - `usedProfile`
  - `totalCount`

`examType` 값:
- `FLASH_CARD`
- `OX_PROBLEM`
- `FIVE_CHOICE`
- `SHORT_ANSWER`
- `DEBATE`

### 시험 생성 (비동기)
- `POST /api/exams/generation/async`
- 권한: `TEACHER`
- 응답: `taskId`, `statusUrl` 등
- 진행 조회: `GET /api/tasks/{taskId}/status`

### 시험 생성 스트리밍
- `GET /api/exams/generation/stream?examSessionId={id}`
- 권한: `TEACHER`
- 응답: `text/event-stream`

### 시험 세션 조회/삭제/복구
- `GET /api/exams/generation/{examSessionId}` (TEACHER)
- `DELETE /api/exams/generation/{examSessionId}` (TEACHER)
- `POST /api/exams/generation/{examSessionId}/recover` (TEACHER)

### 시험 제출/채점
- `POST /api/exams/submission`
- 권한: `STUDENT`
- 요청: `examSessionId`, `answers[]`
- 결과 조회: `GET /api/exams/submission/{examResultId}`

주의:
- 제출 전에 모든 문항 답변 완료 여부를 프론트에서 선검증 권장

---

## 5) 토론형 시험 API

### 시작
- `POST /api/exams/debate/start`
- 권한: `STUDENT` 또는 `TEACHER`
- 요청: `examSessionId`, 선택 `mode`, `topic`

### 응답 전송
- `POST /api/exams/debate/respond`
- 권한: `STUDENT` 또는 `TEACHER`
- 요청: `examSessionId`, `userInput`

---

## 6) 강의자료 생성 API (Phase 1~5)

- 베이스: `/api/materials/generation`
- 권한: 대부분 `TEACHER`

주요 엔드포인트:
- `POST /api/materials/generation/phase1` ~ `phase5`
- `POST /api/materials/generation/async`
- `GET /api/materials/generation/{sessionId}/status`
- `GET /api/materials/generation/{sessionId}/document`
- `GET /api/materials/generation/{sessionId}/progress` (SSE)
- `GET /api/materials/generation/lectures/{lectureId}/latest-session`

---

## 7) 학생 Q&A API

- `POST /api/inquiries/answer`
- 권한: `STUDENT`
- 요청:
  - 필수: `aiQuestionId`, `answerText`
  - 선택: `materialId`

주의:
- 한 강의에 PDF가 여러 개면 `materialId`를 보내는 것을 권장

---

## 8) 강의/코스/자료 API (공통)

### 코스
- `/api/courses/*`
- 예: 코스 목록/상세, `GET /api/courses/{courseId}/contents`

### 강의실 가입 요청 (승인형 등록 — 신규)

학생 입장 흐름이 단일 코드 즉시 등록에서 **교사 승인형**으로 전환됐다. 자세한 가이드는 `docs/handoff/COURSE_JOIN_REQUEST_FE.md` 참조.

| 메서드/경로 | 권한 | 용도 |
|---|---|---|
| `POST /api/courses/join-requests` | STUDENT | body `{ invitationCode }` → PENDING 생성 |
| `GET /api/courses/join-requests/me` | STUDENT | 본인 요청 목록(상태 무관, 최신순, PageResponse) — 응답 항목: `requestId`, `courseId`, `courseTitle`, `status`, `requestedAt`, `updatedAt` |
| `GET /api/courses/{courseId}/join-requests?status=` | TEACHER | 목록 (기본 PENDING, PageResponse) |
| `POST /api/courses/{courseId}/join-requests/{requestId}/approve` | TEACHER | 승인 (Enrollment 자동 생성, 학생에게 `COURSE_JOIN_APPROVED` 알림 발행) |
| `POST /api/courses/{courseId}/join-requests/{requestId}/reject` | TEACHER | 거절 (학생 재요청 가능, `COURSE_JOIN_REJECTED` 알림) |
| `POST /api/courses/{courseId}/join-requests/{requestId}/block` | TEACHER | 차단 (학생 재요청 불가 영구, `COURSE_JOIN_BLOCKED` 알림) |

호환 / 제거:
- **제거됨**: `POST /api/courses/{courseId}/enroll` — 호출부 즉시 제거. 호출하면 404.
- **호환 유지(deprecated)**: `POST /api/courses/join?code=` — 즉시 등록되는 기존 동작 그대로. 신규 사용 금지(이 경로는 알림 흐름을 타지 않음).

신규 에러 코드: `JOIN_REQUEST_PENDING_EXISTS`(409) / `JOIN_REQUEST_BLOCKED`(403) / `ENROLLMENT_ALREADY_EXISTS`(409) / `JOIN_REQUEST_NOT_FOUND`(404) / `JOIN_REQUEST_ALREADY_PROCESSED`(409).

### 알림 (신규)

학생/교사 공용. 진실 원천은 DB 저장 알림이고, SSE 스트림은 실시간 편의용이다(연결이 끊겨도 GET 목록으로 복구 가능).

| 메서드/경로 | 권한 | 용도 |
|---|---|---|
| `GET /api/notifications` | STUDENT/TEACHER | 본인 알림 목록 (최신순, PageResponse). 정렬 허용 필드: `createdAt`. |
| `GET /api/notifications/unread-count` | STUDENT/TEACHER | `{ count }` — 안 읽은 알림 개수 |
| `POST /api/notifications/{notificationId}/read` | STUDENT/TEACHER | 단건 읽음 처리. 본인 알림 아니면 404 |
| `POST /api/notifications/read-all` | STUDENT/TEACHER | 본인 안 읽은 알림 전체 읽음 처리 |
| `GET /api/notifications/stream` | STUDENT/TEACHER | SSE 스트림 (`text/event-stream`). 이벤트 표준: `message` / `heartbeat` / `timeout` / `error` |

알림 응답 필드 (REST · SSE 공통):
- `notificationId`, `type`, `title`, `body`, `resourceType`, `resourceId`, `read`(boolean), `createdAt`

`type` 값(현재 발행되는 것):
- `COURSE_JOIN_APPROVED` / `COURSE_JOIN_REJECTED` / `COURSE_JOIN_BLOCKED` — `resourceType="course"`, `resourceId={courseId}`

FE 권장 흐름:
1. 로그인 직후 `GET /api/notifications/unread-count`로 뱃지 표시
2. 알림 패널 열 때 `GET /api/notifications` 페이지 로드
3. 백그라운드로 `GET /api/notifications/stream` 연결 (`fetch + ReadableStream` 권장 — `EventSource`는 Authorization 헤더 불가). `event:message` 수신 시 목록 prepend + 뱃지 +1
4. 사용자가 알림 클릭 시 `POST /api/notifications/{id}/read`

### 강의
- 생성/수정/삭제: `/api/courses/{courseId}/lectures`, `/api/lectures/{lectureId}`

### 자료
- 업로드: `POST /api/lectures/{lectureId}/materials` (multipart)
- 파일 다운로드: `GET /api/materials/{materialId}/file`

---

## 9) v1 강의 에이전트 API (현재 프론트 사용 대상)

| 메서드 | 경로 | 응답 형식 | 권한 |
|---|---|---|---|
| `POST` | `/api/lectures/{lectureId}/generate-content` | JSON | `TEACHER` |
| `POST` | `/api/lectures/{lectureId}/stream/initialize` | JSON | `TEACHER` |
| `GET`  | `/api/lectures/{lectureId}/stream/next` | **SSE** (`text/event-stream`) | `TEACHER`, `STUDENT` |
| `POST` | `/api/lectures/{lectureId}/stream/answer` | JSON | `TEACHER`, `STUDENT` |
| `POST` | `/api/lectures/{lectureId}/stream/cancel` | 204 | `TEACHER`, `STUDENT` |
| `GET`  | `/api/lectures/{lectureId}/stream/session` | JSON | `TEACHER`, `STUDENT` |
| `GET`  | `/api/lectures/{lectureId}/ai-status` | JSON | `TEACHER`, `STUDENT` |

### stream/next SSE 이벤트 규격

```
event: message
data: {"type":"delta","delta":"텍스트 조각"}

event: done
data: {"type":"done","lectureId":22,"hasMore":false,"waitingForAnswer":false,"chapterTitle":"페이지 설명"}

event: done
data: {"type":"done","status":"WAITING_FOR_ANSWER","waitingForAnswer":true,"hasMore":true,"aiQuestionId":"..."}

event: error
data: {"type":"error","message":"오류 내용"}
```

> `EventSource`는 `Authorization` 헤더를 설정할 수 없으므로 **`fetch` + `ReadableStream`** 방식을 사용한다.

```javascript
const res = await fetch(`/api/lectures/${lectureId}/stream/next`, {
  method: 'GET',
  headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' }
});
const reader = res.body.getReader();
// message 이벤트 수신 시 delta를 화면에 append
// done 이벤트 수신 시 스트림 종료 처리
```

### 프론트 권장 흐름

1. `stream/initialize` — 세션 초기화
2. `stream/next` — SSE 연결, `message` 이벤트마다 텍스트 append
3. `done` 이벤트에서 `waitingForAnswer: true`이면 `stream/answer` 호출
4. `done` 이벤트에서 `hasMore: false`이면 완료
5. 완료 후 `ai-status` 또는 `stream/session` 조회

---

## 10) 프론트 체크리스트

1. 프론트는 반드시 Spring API만 호출한다.
2. v3 통합학습: `USER_MESSAGE`는 **`payload.question`(또는 루트 `question`)** 사용; `text`만 쓰지 말 것 (호환은 서버가 보강).
3. v3: 이벤트 호출 시 **뷰어 페이지**를 쿼리 `page` / `pageNumber` / `currentPage`로 넘길 것.
4. v3 SSE: NDJSON 한 줄이 `data`에 들어오므로 **JSON 파싱 후 `type` 분기**; `done.data.quiz`·`done.data.ui` 처리.
5. v1 `stream/next`: `thought` / `message` / `done` / `error` 이벤트명 구분.
6. SSE는 `heartbeat` 무시, `done`/`error` 분기 처리.
7. 시험 제출 전 답변 누락 검증.
8. 권한 에러(401/403) 공통 핸들러 적용.
9. 강의실 입장: `POST /api/courses/{courseId}/enroll` 호출부 **즉시 제거** (404). `/join?code=`는 호환만 유지, 신규는 `/join-requests` 사용 (`docs/handoff/COURSE_JOIN_REQUEST_FE.md`).
10. 경로/필드 추가 변경 시 Swagger 및 **`llm_multi_agent` Bridge·Session 문서** 기준으로 확인.
