# 학생 상태 조회 + 알림 인프라 — 프론트 인계 문서

이전 라운드(`COURSE_JOIN_REQUEST_FE.md`)에서 보류했던 두 가지가 이번에 추가됐다. **이 문서는 FE가 호출해야 하는 Spring API와 화면 처리 권장만 다룬다.**

- 작성: 2026-04-30
- 백엔드 PR: `feat/v3-springboot` (커밋 `24fe2cb`)
- 백엔드 메인 API 문서: `uou-capstone/FRONTEND_V2_V3_API.md` (§ 강의실 가입 요청 / 알림)
- 직전 인계: `uou-capstone/docs/handoff/COURSE_JOIN_REQUEST_FE.md`

---

## TL;DR (3줄)

1. **신규** — `GET /api/courses/join-requests/me`(학생) — 본인 가입 요청 상태 목록. PENDING/APPROVED/REJECTED/BLOCKED 모두 포함.
2. **신규** — 범용 알림 5종 (`/api/notifications` 목록·unread-count·read·read-all + `/stream` SSE). 학생/교사 공통.
3. **자동 발행** — 교사가 가입 요청을 승인/거절/차단하면 학생에게 알림 1건이 저장되고, SSE 연결 중이면 실시간으로 푸시됨.

이전 문서의 "알림 인프라 없음 / 학생 상태 조회 없음" 두 보류 사항이 모두 해소됐다.

---

## 1) 학생 가입 요청 상태 조회 (신규)

### 1-1. API

| 항목 | 값 |
|---|---|
| 메서드/경로 | `GET /api/courses/join-requests/me` |
| 권한 | `STUDENT` |
| 쿼리 | 페이지네이션 표준 |

쿼리 파라미터:
- `page`: 0-based (기본 0)
- `size`: 1~100 (기본 20)
- `sort`: `createdAt,desc` 형식. 허용 필드 `createdAt`, `updatedAt` (기본 `createdAt,desc`)

성공 응답 `200`:
```json
{
  "content": [
    {
      "requestId": 17,
      "courseId": 4,
      "courseTitle": "소프트웨어 공학 2학기",
      "status": "APPROVED",
      "requestedAt": "2026-04-30T17:42:11",
      "updatedAt":   "2026-04-30T17:55:02"
    },
    {
      "requestId": 12,
      "courseId": 7,
      "courseTitle": "데이터베이스",
      "status": "PENDING",
      "requestedAt": "2026-04-30T11:02:00",
      "updatedAt":   "2026-04-30T11:02:00"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 2,
  "totalPages": 1,
  "first": true,
  "last": true
}
```

응답 필드 노트:
- `status`: `PENDING` / `APPROVED` / `REJECTED` / `BLOCKED` 4종 그대로 노출
- `requestedAt`: 요청 생성 시각(=createdAt)
- `updatedAt`: 마지막 상태 변경 시각. 처리 전이면 `requestedAt`과 동일

발생 가능한 에러:

| HTTP | code | 의미 |
|---|---|---|
| 400 | `INVALID_PARAMETER` | size > 100 또는 허용되지 않은 sort 필드 |

### 1-2. 화면 권장 처리

같은 강의실에 요청 이력이 여러 건일 수 있다(REJECTED → 재요청 → APPROVED 케이스). 화면에서 "현재 상태"만 보여주려면 `courseId`로 그룹핑 후 `requestedAt` 최신 1건을 사용한다.

상태별 라벨 권장:
- `PENDING`: "교사 승인 대기 중"
- `APPROVED`: "승인됨" (강의실로 이동 버튼 활성)
- `REJECTED`: "거절됨" (재요청 버튼 활성)
- `BLOCKED`: "차단됨" (재요청 불가, 교사 문의 안내)

---

## 2) 알림 인프라 (신규 — 학생/교사 공통)

진실 원천은 **DB 저장 알림**이다. SSE 스트림은 실시간 편의용 — 끊겼다가 다시 연결하면 그동안 발생한 알림은 `GET /api/notifications`로 복구한다.

### 2-1. 알림 목록

| 항목 | 값 |
|---|---|
| 메서드/경로 | `GET /api/notifications` |
| 권한 | `STUDENT` 또는 `TEACHER` |
| 쿼리 | 페이지네이션 표준, sort 허용 필드 `createdAt` |

성공 응답 `200`:
```json
{
  "content": [
    {
      "notificationId": 41,
      "type": "COURSE_JOIN_APPROVED",
      "title": "강의실 가입 승인",
      "body": "소프트웨어 공학 2학기 강의실 가입이 승인되었습니다.",
      "resourceType": "course",
      "resourceId": 4,
      "read": false,
      "createdAt": "2026-04-30T17:55:02"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "first": true,
  "last": true
}
```

### 2-2. 안 읽은 개수

| 항목 | 값 |
|---|---|
| 메서드/경로 | `GET /api/notifications/unread-count` |
| 권한 | `STUDENT` 또는 `TEACHER` |

성공 응답 `200`:
```json
{ "count": 3 }
```

뱃지(빨간 점/숫자)에 사용. 로그인 직후 + 알림 패널 닫을 때 갱신을 권장.

### 2-3. 읽음 처리

| 항목 | 값 |
|---|---|
| 메서드/경로 | `POST /api/notifications/{notificationId}/read` |
| 권한 | `STUDENT` 또는 `TEACHER` |
| 요청 바디 | 없음 |
| 응답 | `204 No Content` |

발생 가능한 에러:

| HTTP | code | 의미 |
|---|---|---|
| 404 | `RESOURCE_NOT_FOUND` | 본인 알림이 아니거나 존재하지 않음 |

### 2-4. 모두 읽음 처리

| 항목 | 값 |
|---|---|
| 메서드/경로 | `POST /api/notifications/read-all` |
| 권한 | `STUDENT` 또는 `TEACHER` |
| 요청 바디 | 없음 |
| 응답 | `204 No Content` |

본인의 안 읽은 알림 전체를 한 번에 읽음 처리. 알림 패널의 "모두 읽음" 버튼에 매핑.

---

## 3) 알림 SSE 스트림

### 3-1. API

| 항목 | 값 |
|---|---|
| 메서드/경로 | `GET /api/notifications/stream` |
| 권한 | `STUDENT` 또는 `TEACHER` |
| 응답 Content-Type | `text/event-stream` |
| Idle Timeout | 5분 (서버에서 `event:timeout` 후 종료) |

### 3-2. SSE 이벤트 표준 (`SSE 이벤트 표준 2026-04` 그대로)

| 이벤트 | 의미 | FE 처리 |
|---|---|---|
| `message` | 새 알림 1건 | 알림 목록 prepend + 뱃지 +1 |
| `heartbeat` | 연결 유지 신호 | 무시 (UI 변화 없음) |
| `timeout` | 서버 idle 종료 | 재접속 |
| `error` | 사용자 표시 가능한 오류 | 토스트 + 재접속 |

`done` 이벤트는 알림 스트림에서 발생하지 않는다(영속 채널이라 명시적 종료가 없음). 다른 SSE 스트림(시험 생성/통합 학습)과 다르니 주의.

### 3-3. `event: message` 페이로드

REST 응답과 **동일한 키 셋**:
```json
{
  "notificationId": 41,
  "type": "COURSE_JOIN_APPROVED",
  "title": "강의실 가입 승인",
  "body": "소프트웨어 공학 2학기 강의실 가입이 승인되었습니다.",
  "resourceType": "course",
  "resourceId": 4,
  "read": false,
  "createdAt": "2026-04-30T17:55:02"
}
```

REST와 SSE가 같은 모델이므로 같은 처리 함수를 재사용해도 된다.

### 3-4. 연결 방식 — `EventSource` 금지, `fetch + ReadableStream` 사용

`EventSource`는 Authorization 헤더를 보낼 수 없어 **JWT 인증을 사용하는 본 프로젝트에서는 쓰지 못한다**. `fetch`로 `Accept: text/event-stream` 요청 후 `ReadableStream`을 직접 파싱한다.

```javascript
// 연결 시작
async function openNotificationStream(token, onNotification) {
  const ctrl = new AbortController();

  (async () => {
    while (!ctrl.signal.aborted) {
      try {
        const res = await fetch('/api/notifications/stream', {
          method: 'GET',
          headers: {
            'Authorization': `Bearer ${token}`,
            'Accept': 'text/event-stream',
          },
          signal: ctrl.signal,
        });
        if (!res.ok || !res.body) throw new Error(`stream open failed: ${res.status}`);

        const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
        let buffer = '';
        while (true) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += value;

          // SSE: 이벤트 단위는 빈 줄("\n\n")로 구분
          let idx;
          while ((idx = buffer.indexOf('\n\n')) >= 0) {
            const block = buffer.slice(0, idx);
            buffer = buffer.slice(idx + 2);
            const event = parseSseBlock(block); // { event, data }
            if (event.event === 'message') {
              onNotification(JSON.parse(event.data));
            } else if (event.event === 'timeout' || event.event === 'error') {
              // 즉시 재접속 루프로
              break;
            }
            // heartbeat 는 무시
          }
        }
      } catch (e) {
        if (ctrl.signal.aborted) return;
        // 짧은 backoff 후 재접속
        await new Promise(r => setTimeout(r, 2000));
      }
    }
  })();

  return () => ctrl.abort();
}

function parseSseBlock(block) {
  let event = 'message';
  const dataLines = [];
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
  }
  return { event, data: dataLines.join('\n') };
}
```

연결은 **로그인 후 1회**만 열고 로그아웃·언마운트 시 abort. 재접속은 위 루프 안에서 자동.

---

## 4) 자동 발행 — 어떤 액션이 알림을 만드는가

이번 라운드에서는 **교사의 가입 요청 처리 3종** 만 알림을 발행한다.

| 교사 액션 | 알림 `type` | 받는 사람 | resource |
|---|---|---|---|
| `POST /api/courses/{courseId}/join-requests/{requestId}/approve` | `COURSE_JOIN_APPROVED` | 해당 학생 | `resourceType="course"`, `resourceId={courseId}` |
| `POST /api/courses/{courseId}/join-requests/{requestId}/reject` | `COURSE_JOIN_REJECTED` | 해당 학생 | `resourceType="course"`, `resourceId={courseId}` |
| `POST /api/courses/{courseId}/join-requests/{requestId}/block` | `COURSE_JOIN_BLOCKED` | 해당 학생 | `resourceType="course"`, `resourceId={courseId}` |

호환 경로(`POST /api/courses/join?code=`)는 **알림을 발행하지 않는다** — 즉시 등록되므로 통지할 사용자가 없음.

이외 도메인(material/exam/learning)의 알림은 **이번 라운드에 없음**. 후속 라운드에서 추가 예정.

---

## 5) 화면 권장 흐름

### 5-1. 학생 — 강의실 신청 후 결과 받기

```
[강의실 코드 입력]
    │
    │ POST /api/courses/join-requests
    ▼
[대기 상태 화면 — "교사 승인 대기 중"]
    │
    ├─ 화면 진입 시: GET /api/courses/join-requests/me 로 현재 상태 동기화
    │
    └─ SSE 연결 중이면: event:message 수신 시
       └─ type 이 COURSE_JOIN_APPROVED → 라우팅: 강의실 상세
       └─ type 이 COURSE_JOIN_REJECTED → 토스트 + 재요청 버튼 노출
       └─ type 이 COURSE_JOIN_BLOCKED → 차단 메시지 + 재요청 비활성
```

승인 알림은 SSE 미연결 상태에서도 DB에 저장되므로, 학생이 다음번에 앱에 들어와서 `GET /api/notifications`를 호출하면 동일하게 알 수 있다.

### 5-2. 학생/교사 — 글로벌 알림 패널

```
[헤더의 알림 아이콘]
    │
    ├─ 마운트 시: GET /api/notifications/unread-count → 뱃지
    │           GET /api/notifications/stream 연결 (백그라운드 유지)
    │
    │ 사용자가 아이콘 클릭
    ▼
[알림 패널 열림]
    │
    ├─ GET /api/notifications?page=0&size=20 → 목록 렌더링
    │
    │ 알림 항목 클릭
    ▼
POST /api/notifications/{id}/read   (낙관적 UI: 즉시 read=true 표시)
    │
    │ resourceType + resourceId 로 라우팅
    │   "course" + courseId → 강의실 상세
    │
    │ "모두 읽음" 버튼 클릭
    ▼
POST /api/notifications/read-all → 뱃지 0
```

---

## 6) 뱃지·목록 정합성 룰 (권장)

- SSE `event:message` 수신: 목록 prepend + 뱃지 +1 (낙관적)
- 단건 read 호출 성공: 해당 항목 `read=true`로 갱신 + 뱃지 −1 (이미 0 이상에서만)
- read-all 호출 성공: 뱃지 = 0 + 목록 전체 `read=true` 일괄 적용
- 페이지 전환·새로고침 시: `GET /api/notifications/unread-count`로 한 번 보정 (낙관적 UI 누락 회복)

---

## 7) 점검 체크리스트

- [ ] 학생 가입 신청 후 "대기 화면"에서 `GET /api/courses/join-requests/me`로 상태 진입 시 동기화 추가
- [ ] 글로벌 알림 패널 / 뱃지 컴포넌트 신규 추가
- [ ] SSE 연결을 `EventSource`가 아닌 `fetch + ReadableStream`로 구현 (Authorization 헤더 필수)
- [ ] SSE `timeout`·`error` 수신 시 자동 재접속 루프 동작 확인
- [ ] 알림 `type === 'COURSE_JOIN_APPROVED'` 수신 시 강의실 상세로 라우팅
- [ ] 같은 강의실 요청 이력이 여러 건일 때 학생 화면에서 "현재 상태"는 `requestedAt` 최신 1건 기준으로 표시
- [ ] 호환 경로(`/join?code=`)로 등록된 케이스는 알림이 안 옴을 인지(즉시 등록 화면에는 알림 흐름 없음)

---

## 8) 다음 라운드 예고 (FE 작업 아님)

후속 라운드 후보:
- material/exam/learning 도메인의 알림 발행 연결 (예: AI 콘텐츠 생성 완료, 시험 채점 완료)
- 멀티 인스턴스 운영 전환 시 Redis Pub/Sub 알림 브로드캐스트 도입(현재는 단일 인스턴스 in-memory)
- 푸시 알림(FCM/APNs) 연동
- 알림 보관 만료/자동 정리 배치

이 항목들이 진행될 때는 별도 인계 문서로 다시 정리한다. 이번 SSE 계약·페이로드 스키마는 그대로 유지될 예정이다.
