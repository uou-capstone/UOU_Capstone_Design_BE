# 학생-선생 상호작용 신규 기능 — 프론트 인계 문서

이번 라운드에서 **공지사항 / 토론게시판 / 출석** 3개 도메인이 새로 들어왔다. FE 가 호출해야 하는 Spring API 와
변경 영향만 다룬다.

- 작성: 2026-05-09
- 백엔드 PR: `feat/v3-springboot` (커밋 `842bfb8` ~ `a52b22b`, 5건)
- 백엔드 메인 API 문서: `uou-capstone/FRONTEND_V2_V3_API.md`

---

## TL;DR (5줄)

1. **신규** — 공지사항 CRUD + 댓글 (`/api/courses/{cid}/notices/...`). 교사 작성, 학생 댓글 가능. 1단계 답글까지.
2. **신규** — 토론게시판 CRUD + 댓글 (`/api/courses/{cid}/discussions/...`). 학생도 작성. 상세 조회 시 viewCount +1.
3. **신규** — 출석 회차 + 출석부 (`/api/courses/{cid}/attendance/...`). 회차 생성 시 ACTIVE 수강생 전체 ABSENT 자동.
4. **알림 신규 타입** — `NOTICE_PUBLISHED`, `NOTICE_COMMENT_REPLIED`, `DISCUSSION_COMMENT_RECEIVED`. 기존 SSE 스트림에 추가됨.
5. **공통** — 모든 목록 응답은 `PageResponse<T>` 형식. PATCH body 의 boolean 은 wrapper 타입 (null 허용).

---

## 0. 공통 컨벤션

### 0-1. PageResponse<T> 응답 형식

모든 목록 엔드포인트는 다음 형식으로 응답한다. 기존 `/api/courses/{cid}/join-requests` 등과 동일.

```json
{
  "content": [ /* T 배열 */ ],
  "page": 0,
  "size": 20,
  "totalElements": 42,
  "totalPages": 3,
  "first": true,
  "last": false
}
```

쿼리 파라미터:
- `page` (기본 0)
- `size` (기본 20, 최대 100)
- `sort` — 형식 `field,direction` (예: `sort=createdAt,desc`). 허용 필드는 엔드포인트별 명시.

### 0-2. PATCH body 의 Boolean 은 wrapper 타입

PATCH 부분 갱신에서 `pinned`, `allowComments` 같은 boolean 필드는 **null = "그대로 유지"** 의미.
명시적으로 `false` 로 변경하려면 `false` 를 보내야 한다.

```json
// 핀 고정 해제만 하고 제목·본문은 그대로 두기
PATCH /api/courses/{cid}/notices/{nid}
{ "pinned": false }    // ✅
{ "pinned": null }     // 변경 안 함
{}                      // 변경 안 함
```

### 0-3. 에러 응답 (`BusinessException`)

```json
{
  "code": "4030",
  "message": "접근 권한이 없습니다."
}
```

본 라운드 신규 기능에서 자주 만날 코드:
- `4030 FORBIDDEN` — 권한 없음 (강의실 교사 아님 / ACTIVE 수강생 아님 / 작성자 아님)
- `4040 RESOURCE_NOT_FOUND` — 게시글·댓글·회차 없음, 또는 다른 강의실 ID 끼워넣기
- `4042 COURSE_NOT_FOUND` — 강의실 자체가 없음
- `4043 LECTURE_NOT_FOUND` — 출석 회차 lectureId 검증 실패
- `4000 INVALID_PARAMETER` — 검증 실패 (대댓글의 답글, 다른 강의실 lecture, allowComments=false 댓글 등)

### 0-4. 알림 (Notification SSE) — 신규 타입

기존 SSE 스트림 `GET /api/notifications/stream` 에 새 `type` 값이 흐른다.

| type | 발생 시점 | 대상 (FE 라우팅 키) | resourceType / resourceId |
|---|---|---|---|
| `NOTICE_PUBLISHED` | 교사가 공지 작성 | ACTIVE 수강생 전원 | `"NOTICE"` / `noticeId` — 공지 상세 |
| `NOTICE_COMMENT_REPLIED` | 내 공지 댓글에 답글 | parent 댓글 작성자 | `"NOTICE"` / `noticeId` — 공지 상세 (댓글 섹션 스크롤) |
| `DISCUSSION_COMMENT_RECEIVED` | 내 토론글/댓글에 댓글 | 게시글 작성자 또는 parent 댓글 작성자 | `"DISCUSSION"` / `discussionId` — 토론 상세 |

**FE 처리 가이드**:
- 알림 클릭 시 `resourceType` 으로 라우팅 분기 → `resourceId` 로 상세 페이지 이동
- 댓글 알림(`*_REPLIED`, `*_RECEIVED`)은 본인이 본인에게 단 케이스는 백엔드가 스킵 — FE 추가 필터 불필요
- 알림 `body` 형식: `"{작성자명}: {본문 100자 truncate + …}"` — 그대로 표시 가능

### 0-5. 권한 매트릭스 (요약)

| 도메인 | 조회 | 작성 | 수정 | 삭제 |
|---|---|---|---|---|
| 공지 | 교사+ACTIVE 수강생 | 교사 | 작성 교사 본인 | 작성자 OR 강의실 교사 |
| 공지 댓글 | 교사+ACTIVE 수강생 | 교사+ACTIVE 수강생 | 작성자 본인 | 작성자 OR 강의실 교사 |
| 토론 | 교사+ACTIVE 수강생 | 교사+ACTIVE 수강생 | 작성자 본인 | 작성자 OR 강의실 교사 |
| 토론 댓글 | 교사+ACTIVE 수강생 | 교사+ACTIVE 수강생 (allowComments=true) | 작성자 본인 | 작성자 OR 강의실 교사 |
| 출석 회차 | 교사 | 교사 | 교사 | 교사 |
| 출석 record (조회/수정) | 교사 | — | 교사 (일괄) | — |
| 본인 출석 (`/me`) | ACTIVE 수강생 | — | — | — |

> **ACTIVE 수강생만 허용** — `EnrollmentStatus.COMPLETED`/`DROPPED` 인 학생은 신규 기능 모두 차단됨.
> 기존 자료/시험 권한 체크와 정책 다름 (기존은 status 무시 하고 enrollment 존재만 검증). FE 가 별도 처리할 것 없음 — 백엔드가 게이트.

---

## 1. 공지사항 (Notice)

### 1-1. 엔드포인트

prefix: `/api/courses/{courseId}/notices`

| 메서드 | 경로 | 권한 | 응답 |
|---|---|---|---|
| GET | `.` | 교사+학생 | `PageResponse<NoticeListItemResponseDto>` |
| POST | `.` | 교사 | `NoticeResponseDto` (201) |
| GET | `./{noticeId}` | 교사+학생 | `NoticeResponseDto` |
| PATCH | `./{noticeId}` | 작성 교사 | `NoticeResponseDto` |
| DELETE | `./{noticeId}` | 작성자 OR 강의실 교사 | 204 |
| GET | `./{noticeId}/comments` | 교사+학생 | `PageResponse<NoticeCommentResponseDto>` |
| POST | `./{noticeId}/comments` | 교사+학생 | `NoticeCommentResponseDto` (201) |
| PATCH | `./{noticeId}/comments/{commentId}` | 작성자 | `NoticeCommentResponseDto` |
| DELETE | `./{noticeId}/comments/{commentId}` | 작성자 OR 강의실 교사 | 204 |

**정렬**: 목록 기본 `pinned DESC, createdAt DESC`. 댓글 기본 `createdAt ASC`.
허용 sort 필드: 게시글 — `createdAt`, `updatedAt`, `pinned`. 댓글 — `createdAt`, `updatedAt`.

### 1-2. 요청/응답 스키마

**POST `/api/courses/{cid}/notices`**

```json
// 요청
{
  "title": "중간고사 안내",
  "contentMarkdown": "# 시험 일정\n\n다음 주 목요일 14시...",
  "category": "EXAM",      // GENERAL | EXAM | MATERIAL | ASSIGNMENT (null → GENERAL)
  "priority": "IMPORTANT", // NORMAL | IMPORTANT (null → NORMAL)
  "pinned": true           // null → false
}

// 응답 (201)
{
  "noticeId": 1,
  "courseId": 50,
  "authorTeacherId": 10,
  "authorUserId": 201,
  "authorName": "김교수",
  "title": "중간고사 안내",
  "contentMarkdown": "...",
  "category": "EXAM",
  "priority": "IMPORTANT",
  "pinned": true,
  "createdAt": "2026-05-09T13:00:00.000",
  "updatedAt": "2026-05-09T13:00:00.000"
}
```

> **부수 효과**: ACTIVE 수강생 전원에게 `NOTICE_PUBLISHED` 알림 발송. SSE 로 흐른다.

**GET 목록 응답 (`NoticeListItemResponseDto` — 본문 미포함)**
```json
{
  "content": [
    {
      "noticeId": 1,
      "authorUserId": 201,
      "authorName": "김교수",
      "title": "중간고사 안내",
      "category": "EXAM",
      "priority": "IMPORTANT",
      "pinned": true,
      "createdAt": "...",
      "updatedAt": "..."
    }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "first": true, "last": true
}
```

**PATCH** — 모든 필드 nullable (부분 갱신).

**POST `/comments`**
```json
// 요청 — 일반 댓글
{ "contentMarkdown": "감사합니다." }

// 요청 — 답글
{ "contentMarkdown": "추가 질문이요", "parentCommentId": 5000 }

// 응답
{
  "commentId": 6000,
  "noticeId": 1,
  "authorUserId": 300,
  "authorName": "홍학생",
  "parentCommentId": 5000,
  "contentMarkdown": "추가 질문이요",
  "createdAt": "...",
  "updatedAt": "..."
}
```

### 1-3. 핵심 제약

- **1단계 답글까지만** — 답글의 `parentCommentId` 에 다른 답글 ID 를 넣으면 `400 INVALID_PARAMETER` ("대댓글의 답글은 허용되지 않습니다.")
- **다른 게시글 댓글 ID 끼워넣기 차단** — 다른 noticeId 의 commentId 를 `parentCommentId` 로 보내면 `404 RESOURCE_NOT_FOUND`
- **PATCH 권한** — 공지 수정은 작성 교사 본인만. 다른 교사가 시도하면 `403 FORBIDDEN`. 삭제는 본인 또는 강의실 교사 OK.
- **DELETE cascade** — 공지 삭제 시 댓글·답글 자동 정리 (DB FK ON DELETE CASCADE).

### 1-4. UI 힌트

- 핀 정렬 시 `pinned=true` 가 항상 위. FE 에서 별도 필터 불필요.
- `NoticeCategory` 별 색상·아이콘 표시 권장.
- `NoticePriority=IMPORTANT` 는 강조 처리 (붉은 배지 등).
- 본문은 markdown — 기존 lecture description 렌더러 재사용 가능.

---

## 2. 토론게시판 (Discussion)

### 2-1. 엔드포인트

prefix: `/api/courses/{courseId}/discussions`

| 메서드 | 경로 | 권한 | 응답 |
|---|---|---|---|
| GET | `.` | 교사+학생 | `PageResponse<DiscussionListItemResponseDto>` |
| POST | `.` | 교사+학생 | `DiscussionResponseDto` (201) |
| GET | `./{discussionId}` | 교사+학생 | `DiscussionResponseDto` (**viewCount +1**) |
| PATCH | `./{discussionId}` | 작성자 | `DiscussionResponseDto` |
| DELETE | `./{discussionId}` | 작성자 OR 강의실 교사 | 204 |
| GET | `./{discussionId}/comments` | 교사+학생 | `PageResponse<DiscussionCommentResponseDto>` |
| POST | `./{discussionId}/comments` | 교사+학생 | `DiscussionCommentResponseDto` (201) |
| PATCH | `./{discussionId}/comments/{commentId}` | 작성자 | `DiscussionCommentResponseDto` |
| DELETE | `./{discussionId}/comments/{commentId}` | 작성자 OR 강의실 교사 | 204 |

### 2-2. 요청/응답 스키마

**POST `/api/courses/{cid}/discussions`**
```json
// 요청
{
  "title": "강의 자료 3장 질문",
  "contentMarkdown": "...",
  "category": "QUESTION",   // QUESTION | FREE | RESOURCE (null → FREE)
  "pinned": false,           // null → false
  "allowComments": true      // null → true
}

// 응답 (201)
{
  "discussionId": 1,
  "courseId": 50,
  "authorUserId": 300,        // 학생도 작성 가능 — User.id
  "authorName": "홍학생",
  "title": "...",
  "contentMarkdown": "...",
  "category": "QUESTION",
  "pinned": false,
  "allowComments": true,
  "viewCount": 0,
  "createdAt": "...",
  "updatedAt": "..."
}
```

**GET 상세** — 호출 시마다 `viewCount` 가 1 증가하여 응답에 반영됨. 같은 사용자 반복 조회로도 +1 됨 (1차 정책).

**POST `/comments`** — `allowComments=false` 인 게시글에 댓글 시도 시 `400 INVALID_PARAMETER`.
스키마는 공지 댓글과 동일.

### 2-3. 핵심 제약

- **학생도 작성 가능** — `Discussion.author` 가 `User` 라 학생/교사 모두 가능. 다만 ACTIVE 수강생만.
- **PATCH 권한** — 작성자 본인만. 강의실 교사도 못 함 (자기 글 아닌 한). 삭제는 작성자 또는 강의실 교사.
- **`allowComments=false`** — 작성자가 댓글 비활성화. FE 에서도 입력창 disable 처리 권장.
- **viewCount 1차 정책** — 새로고침 시마다 +1. 추후 24h 디바운스 정책으로 바뀔 수 있음.

### 2-4. UI 힌트

- 카테고리 enum 3개 (QUESTION/FREE/RESOURCE) — 필터·탭 UI.
- `allowComments=false` 게시글은 댓글 입력 영역 자체를 숨기거나 disable.
- 학생이 자기 글에 댓글 달면 알림 미발송 — FE 추가 필터 불필요.

---

## 3. 출석 (Attendance)

### 3-1. 데이터 모델 개요

- **AttendanceSession** — 출석 회차. lecture 매핑 (`lectureId` nullable) 또는 독립 회차.
- **AttendanceRecord** — 학생별 record. `(session, student)` 유니크.
- **AttendanceStatus** — `PRESENT` / `LATE` / `ABSENT` / `EXCUSED`.

**핵심 동작**: 회차 생성 시 ACTIVE 수강생 전원에 대해 ABSENT record 가 자동 생성됨. 이후 교사가 PUT 으로 일괄 갱신.

### 3-2. 엔드포인트

prefix: `/api/courses/{courseId}/attendance`

| 메서드 | 경로 | 권한 | 응답 |
|---|---|---|---|
| GET | `./sessions` | 교사 | `PageResponse<AttendanceSessionResponseDto>` |
| POST | `./sessions` | 교사 | `AttendanceSessionResponseDto` (201) |
| GET | `./sessions/{sessionId}` | 교사 | `AttendanceSessionResponseDto` |
| PATCH | `./sessions/{sessionId}` | 교사 | `AttendanceSessionResponseDto` |
| DELETE | `./sessions/{sessionId}` | 교사 | 204 (cascade로 records 정리) |
| GET | `./sessions/{sessionId}/records` | 교사 | `List<AttendanceRecordResponseDto>` (비페이징) |
| PUT | `./sessions/{sessionId}/records` | 교사 | 204 (일괄 upsert) |
| GET | `./me` | 학생 (ACTIVE) | `StudentAttendanceSummaryResponseDto` |
| GET | `./summary` | 교사 | `CourseAttendanceMatrixResponseDto` |

### 3-3. 요청/응답 스키마

**POST `/sessions`** — 회차 생성
```json
// 요청
{
  "title": "1주차 1차시",
  "sessionDate": "2026-05-09",
  "startTime": "10:00:00",  // 선택
  "endTime": "12:00:00",    // 선택
  "lectureId": 1            // 선택 — 같은 강의실 lecture 만 허용
}

// 응답 (201) — records 는 응답에 포함 안 됨. 별도 GET /records 호출
{
  "sessionId": 100,
  "courseId": 50,
  "lectureId": 1,
  "title": "1주차 1차시",
  "sessionDate": "2026-05-09",
  "startTime": "10:00:00",
  "endTime": "12:00:00",
  "createdByTeacherId": 10,
  "createdAt": "...",
  "updatedAt": "..."
}
```

> **부수 효과**: ACTIVE 수강생 N명에 대해 `status=ABSENT, markedBy=createdBy` record N개 자동 생성.
> **검증**: `lectureId` 가 다른 강의실 lecture 면 `400 INVALID_PARAMETER` ("lecture 가 해당 강의실 소속이 아닙니다.")

**PATCH `/sessions/{sessionId}`** — 메타 수정 (records 영향 없음). `lectureId` 변경 시 동일 검증.

**GET `/sessions/{sessionId}/records`** — 회차의 학생별 record
```json
[
  {
    "recordId": 1000,
    "sessionId": 100,
    "studentId": 100,
    "studentName": "홍학생",
    "status": "ABSENT",
    "markedAt": "2026-05-09T10:00:00",
    "markedByTeacherId": 10,
    "note": null
  }
]
```

**PUT `/sessions/{sessionId}/records`** — 일괄 upsert
```json
// 요청
{
  "items": [
    { "studentId": 100, "status": "PRESENT", "note": null },
    { "studentId": 101, "status": "LATE", "note": "10분 지각" },
    { "studentId": 102, "status": "EXCUSED", "note": "공결" }
  ]
}

// 응답: 204 No Content
```

**동작**:
- items 에 있는 학생 → status/note 갱신
- items 에 없는 학생 → 그대로 유지 (덮어쓰기 아님)
- 신규 student (회차 생성 후 ACTIVE 가 된 학생) → ACTIVE 검증 후 insert
- ACTIVE 가 아닌 학생 ID → `403 FORBIDDEN`
- 다른 강의실 학생 ID → `403 FORBIDDEN`
- 동시 PUT — 분산 락으로 직렬화

**GET `/me`** — 학생 본인 출석 요약
```json
{
  "courseId": 50,
  "totalSessions": 16,
  "presentCount": 14,
  "lateCount": 1,
  "absentCount": 0,
  "excusedCount": 1,
  "presentRatio": 0.875,    // PRESENT / 전체 세션 수
  "sessions": [
    { "sessionId": 100, "title": "1주차 1차시", "sessionDate": "2026-05-09", "status": "PRESENT" },
    // ...
  ]
}
```

> **`presentRatio` 정의**: `PRESENT 개수 / 전체 세션 수`. LATE/EXCUSED 가중 X (1차 단순 정책).
> FE 에서 `lateCount`/`excusedCount` 도 받으니 자체 가중 계산 가능.

**GET `/summary`** — 교사용 매트릭스
```json
{
  "sessions": [
    { "sessionId": 100, "title": "1주차 1차시", "sessionDate": "2026-05-09" },
    { "sessionId": 99, "title": "오리엔테이션", "sessionDate": "2026-05-02" }
  ],
  "students": {       // PageResponse<StudentAttendanceMatrixRowDto>
    "content": [
      {
        "studentId": 100,
        "name": "홍학생",
        "records": { "100": "PRESENT", "99": "LATE" },   // sessionId → status
        "presentRatio": 0.5
      }
    ],
    "page": 0, "size": 50, "totalElements": 1, "totalPages": 1, "first": true, "last": true
  }
}
```

> **응답 형태 주의**: `sessions` 는 비페이징 List, `students` 만 PageResponse. 회차 헤더는 모든 row 가 공유.

### 3-4. 핵심 제약

- **회차 생성 = ABSENT 자동** — FE 가 record 를 따로 생성할 필요 X. 회차 만들면 즉시 출석부가 ABSENT 로 채워짐.
- **lectureId 검증** — 같은 강의실 lecture 만. 다른 강의실 lecture ID 보내면 `400`.
- **lecture 삭제 시** — 출석 세션은 보존, `lectureId` 만 NULL 로 떨어짐 (의도된 동작 — 출석 기록 보존).
- **PUT records 부분 업데이트** — items 에 일부 학생만 보내도 OK. 누락된 학생은 기존 status 유지.
- **status enum 4종만** — UNMARKED 없음. "아직 체크 안 됨"은 `ABSENT` 로 표현 (회차 생성 시 자동).

### 3-5. UI 힌트

- 출석부 화면: 회차 좌측, 학생 row, 셀 = status. 매트릭스 응답 그대로 사용 가능.
- 학생 페이징은 50 기본 — 보통 한 강의실당 학생 수가 적어서 1페이지로 끝나는 경우 많음.
- 출석률 표시: `presentRatio` 그대로 사용 + `lateCount`/`excusedCount` 부가 표시 권장.
- 회차 생성 폼: lectureId 는 선택 (드롭다운 + "독립 회차" 옵션).
- "지각이지만 출석 인정" 같은 정책은 FE 에서 가중 계산 (LATE 도 PRESENT 로 카운트). 백엔드는 단순 PRESENT 만.

---

## 부록 A — NotificationType enum 전체

기존 + 본 라운드 신규 (varchar 칼럼이라 enum 추가만으로 추가됨):

```ts
type NotificationType =
  // 기존
  | 'COURSE_JOIN_APPROVED'
  | 'COURSE_JOIN_REJECTED'
  | 'COURSE_JOIN_BLOCKED'
  | 'COURSE_MEMBER_REMOVED'
  | 'COURSE_MEMBER_BLOCKED'
  // 신규 (이번 라운드)
  | 'NOTICE_PUBLISHED'
  | 'NOTICE_COMMENT_REPLIED'
  | 'DISCUSSION_COMMENT_RECEIVED';
```

`resourceType` 매핑:
- `NOTICE_*` → `"NOTICE"`, `resourceId = noticeId`
- `DISCUSSION_*` → `"DISCUSSION"`, `resourceId = discussionId`

라우팅 예시:
```ts
function routeOnClick(n: NotificationItem) {
  switch (n.resourceType) {
    case 'NOTICE':
      // courseId 가 페이로드에 없음 — 클라이언트 컨텍스트 또는 별도 fetch 필요
      router.push(`/notices/${n.resourceId}`);
      break;
    case 'DISCUSSION':
      router.push(`/discussions/${n.resourceId}`);
      break;
    // ... 기존 COURSE_JOIN_* 등
  }
}
```

> **주의**: `Notification` 페이로드에 `courseId` 가 없다. 각 도메인 상세 API 가 `courseId` path variable 을 요구하므로
> 알림에서 상세로 점프하려면 FE 가 별도로 매핑하거나, 상세 페이지 진입 시 noticeId/discussionId 만으로 백엔드가 강의실을
> 찾도록 하는 보조 엔드포인트가 필요. 현재는 후자가 없음 — **클라이언트 라우팅에서 강의실 컨텍스트를 보존**해야 한다.
> (예: 알림 발생 시점의 라우팅 path 에 courseId 가 포함되어 있다면 그걸 사용)

---

## 부록 B — 마이그레이션·롤아웃 영향

본 라운드는 **순수 추가** — 기존 엔드포인트·응답 형식·인증 흐름 변경 없음.

- ✅ 기존 `/api/courses/...` 호출은 그대로
- ✅ 기존 알림 SSE 스트림 그대로 (단, 새 type 값이 흐름)
- ✅ 기존 V1 schema 변경 없음 — V2 가 신규 6개 테이블만 추가
- ⚠️ FE 의 `NotificationType` 매핑 enum 에 3개 추가 필요 — 안 하면 알림 클릭 시 미정의 라우팅
- ⚠️ 기존 `Notification` 응답 구조 그대로 (`type`, `title`, `body`, `resourceType`, `resourceId`, `readAt`) — 새 type 만 처리하면 됨

---

## 부록 C — 자주 만날 시나리오 → 응답 매핑

| 시나리오 | 결과 |
|---|---|
| 학생이 다른 강의실 공지 ID 로 GET | `404 RESOURCE_NOT_FOUND` |
| DROPPED 학생이 토론 작성 시도 | `403 FORBIDDEN` |
| 다른 학생 댓글에 PATCH | `403 FORBIDDEN` |
| 답글의 답글 작성 (`parentCommentId` 가 답글) | `400 INVALID_PARAMETER` |
| `allowComments=false` 게시글에 댓글 | `400 INVALID_PARAMETER` |
| 출석 회차 생성 시 다른 강의실 lecture 지정 | `400 INVALID_PARAMETER` |
| 출석부에 비ACTIVE 학생 ID 끼워넣기 | `403 FORBIDDEN` |
| `pinned` 가 wrapper 라 명시 false 안 보내고 PATCH | 변경 없음 (`null` 무시) |
| 토론 상세 반복 조회 | viewCount 매번 +1 (1차 정책) |

---

## 후속 후보 (현재 미지원)

명시적으로 제외 — 백엔드에서 알리면 그때 FE 작업:
- 공지 예약 발행 (`status: DRAFT/PUBLISHED`, `publishAt`)
- 토론 view receipt 24h 디바운스
- 댓글 좋아요·반응
- 첨부파일
- 학생용 출석 요청 / 자기 체크인
- 출석률 가중 정책 (LATE 가중치, EXCUSED 제외 옵션)
- 알림 페이로드에 courseId 포함

문의는 백엔드 핸들러 (`feat/v3-springboot` 브랜치 owner) 에게.
