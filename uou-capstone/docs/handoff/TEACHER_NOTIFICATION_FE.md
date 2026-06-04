# 교사 알림 확장 — 프론트 인계 문서

이전 라운드(`NOTIFICATION_FE.md`)에서 알림 인프라(목록/읽음/SSE)는 학생/교사 공통으로 제공됐지만, **실제 알림이 발행되는 곳은 학생을 향한 8종**뿐이었다. 이번 라운드에 **교사용 자동 발행 8종 + 자기 작업 확인 알림 1종**, 그리고 **수신 설정 API 2종**을 추가했다.

- 작성: 2026-05-12
- 백엔드 PR: `feat/v3-springboot` (마이그레이션 `V4__teacher_notification_prefs_and_type_widen.sql`)
- 백엔드 메인 API 문서: `uou-capstone/FRONTEND_V2_V3_API.md`
- 직전 인계: `uou-capstone/docs/handoff/NOTIFICATION_FE.md`

---

## TL;DR (3줄)

1. **신규 알림 타입 9종** — 교사가 SSE/`/api/notifications` 로 받게 됨. 새 `type` 값을 라우팅 매핑에 추가만 하면 됨.
2. **신규 API 2종** — `GET /api/notifications/teacher-preferences`, `PATCH …` (TEACHER 권한). 단일 토글 `includeSelfActionNotifications` (기본 `false`).
3. **인프라 동일** — 기존 `/api/notifications`, `/stream`, `unread-count`, `read`, `read-all` 그대로. 교사 응답 포맷 동일 (`NotificationItemDto`).

기존 학생 흐름은 변경 없음 — **회귀 없음**. FE는 새 타입 라우팅 + 설정 화면(선택)만 작업.

---

## 1) 신규 NotificationType 9종

기존 학생용 8종(`COURSE_JOIN_APPROVED|REJECTED|BLOCKED`, `COURSE_MEMBER_REMOVED|BLOCKED`, `NOTICE_PUBLISHED`, `NOTICE_COMMENT_REPLIED`, `DISCUSSION_COMMENT_RECEIVED`)에 더해, 다음 9종이 발행된다.

| `type` | recipient | 발행 시점 | `resourceType` | `resourceId` | 권장 라우팅 |
|---|---|---|---|---|---|
| `COURSE_JOIN_REQUESTED` | 강의실 담당 교사 | 학생이 가입 요청 생성 | `course` | courseId | 가입 요청 관리 페이지 |
| `DISCUSSION_CREATED` | 강의실 담당 교사 | 학생이 토론글 작성 | `DISCUSSION` | discussionId | 토론글 상세 |
| `DISCUSSION_COMMENTED` | 강의실 담당 교사 | 학생이 토론 댓글 작성 | `DISCUSSION` | discussionId | 토론글 상세 (댓글 영역) |
| `NOTICE_COMMENTED` | 강의실 담당 교사 | 학생이 공지 댓글 작성 | `NOTICE` | noticeId | 공지 상세 (댓글 영역) |
| `ASSESSMENT_SUBMITTED` | 강의실 담당 교사 | 학생 과제 제출 | `ASSESSMENT` | assessmentId | 과제 제출 현황 |
| `EXAM_SUBMITTED` | 강의실 담당 교사 | 학생 시험 응시 완료 | `EXAM` | examSessionId | 시험 결과 페이지 |
| `AI_GENERATION_COMPLETED` | 요청 강의실의 담당 교사 | AI 자료/시험 비동기 생성 완료 | `material` 또는 `exam` | sessionId / examSessionId | 자료 보기 / 시험 보기 |
| `AI_GENERATION_FAILED` | 요청 강의실의 담당 교사 | AI 자료/시험 비동기 생성 실패 | `material` 또는 `exam` | sessionId / examSessionId | 재시도 화면 / 토스트 |
| `TEACHER_ACTION_CONFIRMED` | 교사 본인 | **설정 ON 시에만** 본인 작업 확인 | 작업별 상이 | 작업별 상이 | 토스트 또는 일반 목록 |

### 1-1. 라우팅 매핑 예시 (TS)

```ts
function notificationRoute(n: NotificationItem) {
  switch (n.type) {
    case 'COURSE_JOIN_REQUESTED':
      return `/teacher/courses/${n.resourceId}/join-requests`;
    case 'DISCUSSION_CREATED':
    case 'DISCUSSION_COMMENTED':
      return `/courses/{courseId}/discussions/${n.resourceId}`; // courseId 는 보유 컨텍스트로 보강
    case 'NOTICE_COMMENTED':
      return `/courses/{courseId}/notices/${n.resourceId}`;
    case 'ASSESSMENT_SUBMITTED':
      return `/teacher/assessments/${n.resourceId}/submissions`;
    case 'EXAM_SUBMITTED':
      return `/teacher/exams/${n.resourceId}/results`;
    case 'AI_GENERATION_COMPLETED':
    case 'AI_GENERATION_FAILED':
      return n.resourceType === 'exam'
        ? `/teacher/exam-sessions/${n.resourceId}`
        : `/teacher/material-sessions/${n.resourceId}`;
    case 'TEACHER_ACTION_CONFIRMED':
      return null; // 토스트로만 처리 권장 (resourceType 으로 분기 가능)
    default:
      return /* 기존 학생 알림 라우팅 그대로 */;
  }
}
```

> **주의**: `resourceType` 대소문자 — `DISCUSSION`, `NOTICE`, `ASSESSMENT`, `EXAM` 은 대문자. `course`, `material`, `exam` (AI 생성) 은 소문자. 이는 기존 학생용 알림과 일치하도록 도메인별 기존 컨벤션을 따른 것.

---

## 2) 교사 알림 수신 설정 API (신규 2종)

### 2-1. 조회

| 항목 | 값 |
|---|---|
| 메서드/경로 | `GET /api/notifications/teacher-preferences` |
| 권한 | `TEACHER` |
| 인증 | JWT 헤더 |

성공 응답 `200`:
```json
{ "includeSelfActionNotifications": false }
```

설정 row 가 없으면 서버가 기본값(`false`)으로 lazy-create 후 반환. **별도 초기화 호출 불필요.**

### 2-2. 변경

| 항목 | 값 |
|---|---|
| 메서드/경로 | `PATCH /api/notifications/teacher-preferences` |
| 권한 | `TEACHER` |

요청 바디:
```json
{ "includeSelfActionNotifications": true }
```

성공 응답 `200`: 위 GET 과 동일한 형태(현재 설정값).

### 2-3. 의미

- `false` (기본값): 학생/시스템 이벤트 알림만 받음. 교사 본인이 토론글을 작성해도 본인에게는 알림 안 감.
- `true`: 본인 작업 완료 시 추가로 `TEACHER_ACTION_CONFIRMED` 알림이 본인에게 발행됨. 강의실/공지/토론/자료/평가 등의 본인 CRUD 완료 확인용.

> **현재 적용 범위**: AI 자료/시험 생성 등 학생 측 행동이 아닌 교사 본인 작업의 경우, Publisher 가 자동으로 `TEACHER_ACTION_CONFIRMED` 로 변환해 발행한다 (자세한 발행 지점은 후속 라운드에 점진 확대 예정). 초기엔 토론/공지 등 학생 이벤트가 우연히 본인 작성일 때만 분기 발동.

---

## 3) 기존 인프라 (변경 없음)

아래는 그대로 동작. 학생/교사 모두 동일하게 사용:

- `GET /api/notifications` — 목록 (페이지네이션, `sort=createdAt,desc`)
- `GET /api/notifications/unread-count` — 미읽음 개수
- `POST /api/notifications/{notificationId}/read` — 단건 읽음
- `POST /api/notifications/read-all` — 전체 읽음
- `GET /api/notifications/stream` — SSE 스트림 (`event: message|heartbeat|timeout|error|done`)

응답 DTO `NotificationItemDto` 도 동일:
```json
{
  "notificationId": 1,
  "type": "DISCUSSION_CREATED",
  "title": "새 토론 게시글",
  "body": "alice: 배열 정렬 문제 풀이…",
  "resourceType": "DISCUSSION",
  "resourceId": 42,
  "read": false,
  "createdAt": "2026-05-12T17:30:00"
}
```

> SSE 미연결 중 발생한 알림은 `GET /api/notifications` 로 전체 복구 가능 (기존과 동일).

---

## 4) 권장 화면 작업 (선택)

1. **알림 라우팅 매핑 갱신** (필수) — 위 §1-1 예시 매핑 적용. 미매핑 시 새 타입은 클릭해도 라우팅 안 됨.
2. **교사 설정 화면** (권장) — 마이페이지 또는 알림 페이지 상단에 토글 1개:
   ```tsx
   <Toggle
     label="내가 만든 작업의 완료 알림 받기"
     value={pref.includeSelfActionNotifications}
     onChange={(v) => patchTeacherPreferences({ includeSelfActionNotifications: v })}
   />
   ```
3. **AI 생성 진행 화면** — 기존 진행률 SSE는 변경 없음. 완료/실패 알림은 추가로 일반 알림 목록과 SSE 스트림에 표시되므로, 사용자가 페이지를 떠난 후에도 결과를 알 수 있음.

---

## 5) 검증 시나리오 (백엔드에서 통과한 시나리오)

1. 교사 A 로그인 → `GET /api/notifications/teacher-preferences` → `{ includeSelfActionNotifications: false }`
2. `PATCH … { includeSelfActionNotifications: true }` → 200, 재 GET 시 true
3. `/api/notifications/stream` 연결
4. 학생 B 가 A 의 강의실에서 토론글 작성 → SSE message 수신 (`type: "DISCUSSION_CREATED"`)
5. 교사 A 본인이 공지 작성 → SSE 수신 (true 설정이라 `TEACHER_ACTION_CONFIRMED`)
6. 설정 false 후 같은 작업 → SSE 미발행

---

## 6) 마이그레이션 영향

- DB: `notifications.type` 컬럼이 `ENUM(...)` 에서 `VARCHAR(40)` 으로 변경됨 (V4). 데이터 보존 (값은 모두 enum name 문자열). 운영 배포 시 다운타임 0 권장 (테이블 metadata 변경만, 데이터 마이그레이션 없음).
- 기존 학생 알림 8종은 코드/응답 모두 변경 없음. 기존 라우팅·UI·테스트는 그대로 유지.
- FE 빌드는 새 enum 값을 모르더라도 기존 화면 동작에 영향 없음 (switch 기본 분기 처리 시).
