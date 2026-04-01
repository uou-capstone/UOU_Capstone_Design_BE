# 프론트엔드 API 가이드 (FE ↔ Spring)

이 문서는 **프론트엔드가 호출해야 하는 Spring API만** 정리한다.  
내부 AI 서비스(FastAPI) 연동 경로는 문서화하지 않는다.

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

### 세션 조회/생성
- `POST /api/learning/sessions/{lectureId}`
- 권한: `STUDENT` 또는 `TEACHER`
- 쿼리(선택):
  - `pdfPath`: 세션 초기 연결 시 사용할 PDF 경로
  - `sessionId`: 기존 세션 재사용 ID
- 응답 예시 필드:
  - `session_id`, `lecture_id`, `current_page`, `ai_status_connected`, `created_at`, `updated_at`

### 이벤트 전송 + SSE 스트리밍
- `POST /api/learning/sessions/{sessionId}/event`
- 권한: `STUDENT` 또는 `TEACHER`
- Content-Type: `application/json`
- Accept: `text/event-stream`
- 쿼리(선택): `lectureId`

요청 바디:
- 필수: `type`
- 선택: 이벤트별 데이터 필드 자유 확장

예시:
```json
{
  "type": "USER_MESSAGE",
  "text": "이 페이지 설명해줘"
}
```

SSE 처리 가이드:
- `data:` 라인 JSON 파싱
- `type=heartbeat`는 무시
- `type=agent_delta`는 누적 렌더링
- `type=done`에서 완료 처리
- `type=error`에서 에러 처리

주요 이벤트 타입:
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
2. SSE는 `heartbeat` 무시, `done`/`error` 분기 처리.
3. 시험 제출 전 답변 누락 검증.
4. 권한 에러(401/403) 공통 핸들러 적용.
5. 경로/필드 추가 변경 시 Swagger 기준으로 우선 확인.
