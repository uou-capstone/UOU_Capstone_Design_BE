# 강의실 멤버십·가입 요청 라운드 — 프론트 인계 문서

이 라운드의 BE 변경사항: (1) 가입 요청 응답의 `requestedAt` 시간 형식 통일,
(2) 교사용 수강생 관리 API 신규(목록/제거/차단), (3) 가입 요청 일괄 승인·거절 추가,
(4) 강의실 멤버십 변경 알림 2종(`COURSE_MEMBER_REMOVED` / `COURSE_MEMBER_BLOCKED`).

- 작성: 2026-05-06
- 백엔드 브랜치: `feat/v3-springboot`
- 직전 인계 문서: `docs/handoff/COURSE_JOIN_REQUEST_FE.md` (가입 요청 흐름)
- 메인 API 문서: `uou-capstone/FRONTEND_V2_V3_API.md`

---

## TL;DR (5줄)

1. **시간 포맷 변경 (Breaking)** — 가입 요청 응답의 `requestedAt` / `updatedAt` 가 `LocalDateTime` 문자열(`2026-04-30T17:42:11`)에서 **ISO-8601 offset 포함 UTC 문자열(`2026-04-30T17:42:11.123Z`)**로 바뀜. `new Date(...)` 파싱은 그대로 동작하지만 타임존 처리 코드가 있다면 검증 필요.
2. **신규 — 수강생 목록** : `GET /api/courses/{courseId}/students` (교사 전용, 페이지네이션).
3. **신규 — 수강생 제거** : `DELETE /api/courses/{courseId}/students/{studentId}` (재가입 가능).
4. **신규 — 수강생 차단** : `POST /api/courses/{courseId}/students/{studentId}/block` (수강 관계 끊고 재가입까지 차단).
5. **신규 — 가입 요청 일괄 처리** : `POST /api/courses/{courseId}/join-requests/bulk/{approve|reject}` (한 번에 최대 100건, 부분 실패 허용).

---

## 1) `requestedAt` 시간 포맷 변경 (Breaking)

### 1-1. 영향 범위

다음 3개 응답에서 시간 필드 형식이 바뀌었다.

| API | 변경 필드 |
|---|---|
| `POST /api/courses/join-requests` | `requestedAt` |
| `GET /api/courses/join-requests/me` | `requestedAt`, `updatedAt` |
| `GET /api/courses/{courseId}/join-requests` | `content[].requestedAt` |

### 1-2. 변경 내용

```diff
- "requestedAt": "2026-04-30T17:42:11"            // LocalDateTime, 타임존 미명시
+ "requestedAt": "2026-04-30T17:42:11.123456Z"    // ISO-8601, UTC 명시
```

- 값은 항상 **UTC 기준**의 ISO-8601 offset(`Z` = `+00:00`) 포함 문자열.
- `Date`, `dayjs`, `date-fns` 등 표준 파서는 그대로 동작.
- 기존에 `new Date('2026-04-30T17:42:11')` 처럼 **타임존이 빠진 값에 의존하는 로직**(예: 로컬 시간 가정)이 있으면 표시 결과가 9시간(KST) 차이날 수 있으니 확인 필요.
- BE 캐논컬 필드는 **`requestedAt` 하나뿐**이며 `createdAt` alias 는 추가하지 않는다.

### 1-3. FE 권장 처리

- 사용자에게 보일 때는 로컬 타임존으로 변환:
  ```javascript
  const formatted = new Date(item.requestedAt).toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul',
  });
  ```
- 정렬/비교는 ISO 문자열을 그대로 비교해도 되고, `Date` 로 변환해도 동일한 결과.

---

## 2) 수강생 관리 API 신규 (교사 전용)

### 2-1. 수강생 목록 조회

| 항목 | 값 |
|---|---|
| 메서드/경로 | `GET /api/courses/{courseId}/students` |
| 권한 | `TEACHER` (본인 소유 강의실만) |
| 쿼리 | 페이지네이션 표준 |

쿼리 파라미터:
- `page`: 0-based (기본 0)
- `size`: 1~100 (기본 20)
- `sort`: `createdAt,desc` 형식. 허용 필드 `createdAt` (= 등록 시각). 기본 `createdAt,desc`

성공 응답 `200`:
```json
{
  "content": [
    {
      "enrollmentId": 88,
      "studentId": 9,
      "studentName": "김민수",
      "studentEmail": "minsu@example.com",
      "enrolledAt": "2026-04-30T17:42:11.123456Z"
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

발생 가능한 에러:

| HTTP | code | 의미 |
|---|---|---|
| 403 | `4030` (FORBIDDEN) | 본인 소유가 아닌 강의실 |
| 404 | `4042` (COURSE_NOT_FOUND) | 잘못된 courseId |
| 400 | `4000` (INVALID_PARAMETER) | size > 100 또는 허용되지 않은 sort 필드 |

---

### 2-2. 수강생 제거 (재가입 가능)

| 항목 | 값 |
|---|---|
| 메서드/경로 | `DELETE /api/courses/{courseId}/students/{studentId}` |
| 권한 | `TEACHER` (본인 소유 강의실만) |
| 요청 바디 | 없음 |
| 응답 | `204 No Content` |

동작:
1. 교사 소유 검증 → 403 / 404
2. `(courseId, studentId)` 활성 Enrollment 조회 → 없으면 `404 MEMBER_NOT_FOUND`
3. Enrollment row 삭제
4. 학생에게 `COURSE_MEMBER_REMOVED` 알림 발행
5. 감사 로그 기록 (운영 추적용)

발생 가능한 에러:

| HTTP | code | 의미 |
|---|---|---|
| 403 | `4030` (FORBIDDEN) | 본인 소유가 아닌 강의실 |
| 404 | `4042` (COURSE_NOT_FOUND) | 잘못된 courseId |
| 404 | `4041` (MEMBER_NOT_FOUND) | 해당 학생이 강의실에 등록되어 있지 않음 |

> 제거된 학생은 다시 `POST /api/courses/join-requests` 로 가입 요청을 보낼 수 있다. **차단**이 필요하면 2-3 의 block 엔드포인트를 사용한다.

---

### 2-3. 수강생 차단 (재가입 불가)

| 항목 | 값 |
|---|---|
| 메서드/경로 | `POST /api/courses/{courseId}/students/{studentId}/block` |
| 권한 | `TEACHER` (본인 소유 강의실만) |
| 요청 바디 | 없음 |
| 응답 | `204 No Content` |

동작:
1. 교사 소유 검증 → 403 / 404
2. 학생 존재 확인 → `404 MEMBER_NOT_FOUND`
3. 활성 Enrollment 가 있으면 함께 삭제
4. 같은 (student, course) 의 PENDING 가입 요청은 일괄 BLOCKED 로 정리
5. BLOCKED 마커가 없으면 신규 BLOCKED 가입 요청 row 등록 — **이후 학생이 가입 요청 보내면 `403 JOIN_REQUEST_BLOCKED`**
6. 학생에게 `COURSE_MEMBER_BLOCKED` 알림 발행
7. 감사 로그 기록

발생 가능한 에러:

| HTTP | code | 의미 |
|---|---|---|
| 403 | `4030` (FORBIDDEN) | 본인 소유가 아닌 강의실 |
| 404 | `4042` (COURSE_NOT_FOUND) | 잘못된 courseId |
| 404 | `4041` (MEMBER_NOT_FOUND) | 해당 학생을 찾을 수 없음 (수강 중이 아니어도 차단 가능) |

> 차단은 영구적이고 idempotent — 이미 차단된 학생에게 다시 호출해도 204.

---

## 3) 가입 요청 일괄 승인/거절 (교사 전용)

학기 초 대량 처리용. 한 번에 최대 100건, 한 건 실패가 다른 건을 막지 않는다(개별 트랜잭션).

### 3-1. 일괄 승인

| 항목 | 값 |
|---|---|
| 메서드/경로 | `POST /api/courses/{courseId}/join-requests/bulk/approve` |
| 권한 | `TEACHER` (본인 소유 강의실만) |
| Content-Type | `application/json` |

요청 바디:
```json
{
  "requestIds": [17, 18, 19, 20]
}
```

성공 응답 `200`:
```json
{
  "successCount": 3,
  "failureCount": 1,
  "results": [
    { "requestId": 17, "success": true,  "errorCode": null },
    { "requestId": 18, "success": true,  "errorCode": null },
    { "requestId": 19, "success": false, "errorCode": "4094" },
    { "requestId": 20, "success": true,  "errorCode": null }
  ]
}
```

`errorCode` 는 기존 BusinessException 의 코드 그대로(`4094` = `JOIN_REQUEST_ALREADY_PROCESSED` 등). 매핑:

| code | 의미 |
|---|---|
| `4053` | `JOIN_REQUEST_NOT_FOUND` — 해당 courseId 에 속하지 않거나 존재하지 않음 |
| `4094` | `JOIN_REQUEST_ALREADY_PROCESSED` — 이미 PENDING 이 아닌 상태 |
| `4030` | `FORBIDDEN` — 본인 소유가 아닌 강의실 (이 경우 보통 전체 실패) |

전체 실패는 일반 BusinessException 으로 떨어질 수 있으므로(예: `404 COURSE_NOT_FOUND` / `403 FORBIDDEN`), HTTP 200 응답은 "쳘비스 검증 통과 후 항목별 처리 결과" 라고 이해하면 됨.

### 3-2. 일괄 거절

| 항목 | 값 |
|---|---|
| 메서드/경로 | `POST /api/courses/{courseId}/join-requests/bulk/reject` |
| 권한 | `TEACHER` (본인 소유 강의실만) |
| 요청/응답 | 일괄 승인과 동일한 형식 |

거절된 학생은 동일 강의실에 다시 가입 요청을 보낼 수 있다.

### 3-3. 검증 실패

| HTTP | code | 의미 |
|---|---|---|
| 400 | `4000` | `requestIds` 비어 있음 또는 100개 초과 |
| 403 | `4030` | 본인 소유가 아닌 강의실 |
| 404 | `4042` | 잘못된 courseId |

### 3-4. 권장 사용 패턴

- FE 에서는 체크박스 다중 선택 → 한 번의 호출로 처리.
- `failureCount > 0` 이면 결과 모달에 **실패 목록만 별도로 표시**하고, "실패한 N건은 다른 사용자가 이미 처리했을 수 있습니다. 목록을 새로고침하세요." 안내 권장.
- 처리 후 가입 요청 목록을 재조회 (`GET /api/courses/{courseId}/join-requests?status=PENDING`).

---

## 4) 신규 알림 타입

| 타입 | 발생 시점 | 알림 내용(서버 기본) |
|---|---|---|
| `COURSE_MEMBER_REMOVED` | 교사가 학생을 강의실에서 제거 | "{강의실명} 강의실에서 제거되었습니다. 필요 시 다시 가입 요청을 보낼 수 있습니다." |
| `COURSE_MEMBER_BLOCKED` | 교사가 학생을 강의실에서 차단 | "{강의실명} 강의실에서 차단되었습니다. 더 이상 같은 강의실에 가입 요청을 보낼 수 없습니다." |

알림 페이로드(`GET /api/notifications`):
- `type`: 위 enum 명
- `resourceType`: `"course"`
- `resourceId`: courseId

FE 라우팅: `resourceType === 'course'` 일 때 강의실 목록 또는 학생 마이페이지로 이동.

> 기존 가입 요청 알림(`COURSE_JOIN_APPROVED` / `COURSE_JOIN_REJECTED` / `COURSE_JOIN_BLOCKED`)은 그대로.

---

## 5) 화면 시나리오 — 교사 강의실 관리 탭(권장)

```
[강의실 상세 — 수강생 탭]
    │
    │ 강의실 진입
    ▼
GET /api/courses/{courseId}/students?page=0&size=20
    │
    │ 목록 렌더링 (이름·이메일·등록 시각)
    ▼
[행마다 액션 버튼: 제거 / 차단]
    │
    │ "제거" 클릭 → 확인 모달("재가입은 가능합니다")
    │ "차단" 클릭 → 확인 모달("재가입을 영구 차단합니다")
    ▼
DELETE  /api/courses/{courseId}/students/{studentId}
POST    /api/courses/{courseId}/students/{studentId}/block
    │
    └─ 204 → 목록에서 행 제거 + 토스트 표시
```

```
[강의실 상세 — 가입 요청 탭]  (기존 화면 강화)
    │
    │ 강의실 진입
    ▼
GET /api/courses/{courseId}/join-requests?status=PENDING
    │
    │ 헤더에 [전체 선택] 체크박스 + 행마다 체크박스
    ▼
[하단 일괄 액션 바: 일괄 승인 / 일괄 거절]
    │
    │ 클릭 → 확인 모달 (대상 N명)
    ▼
POST /api/courses/{courseId}/join-requests/bulk/{approve|reject}
    │ body: { requestIds: [선택된 ID들] }
    │
    └─ 200 → results 순회
            ├─ success: 행 제거
            └─ failure: 해당 행에 에러 배지 + 사유(errorCode 매핑)
```

---

## 6) 마이그레이션 단계

| 단계 | FE 작업 | BE 상태 |
|---|---|---|
| **0 (배포 직후)** | 가입 요청 응답의 `requestedAt` 형식 변경 적응(파싱 검증) | 신규 API 5종 활성, 알림 2종 활성 |
| **1 (선택)** | 교사 강의실에 "수강생 탭" 신규 추가 | 변경 없음 |
| **2 (선택)** | 가입 요청 탭에 일괄 처리 UI 추가 | 변경 없음 |

단계 1, 2 는 FE 페이스에 따라 진행 가능. BE 는 단일 API 와 일괄 API 모두 유지한다.

---

## 7) 에러 코드 통합 표 (이번 라운드 신규)

| code | HTTP | 의미 | 발생 위치 |
|---|---|---|---|
| `4041` (MEMBER_NOT_FOUND) | 404 | 학생 또는 수강 관계 없음 | 수강생 제거/차단 |
| `4042` (COURSE_NOT_FOUND) | 404 | 잘못된 courseId | 모든 신규 엔드포인트 |
| `4030` (FORBIDDEN) | 403 | 본인 소유가 아닌 강의실 | 모든 신규 엔드포인트 |
| `4000` (INVALID_PARAMETER) | 400 | size>100, requestIds 형식 오류 | 목록 조회, 일괄 처리 |
| `4053` (JOIN_REQUEST_NOT_FOUND) | 404 | 일괄 처리 부분 실패 — 요청 없음 | 일괄 승인/거절 results |
| `4094` (JOIN_REQUEST_ALREADY_PROCESSED) | 409 | 일괄 처리 부분 실패 — 이미 처리됨 | 일괄 승인/거절 results |

응답 본문 형식(전체 실패 시)은 기존 표준 그대로:
```json
{
  "timestamp": "2026-05-06T10:00:00",
  "status": 403,
  "error": "FORBIDDEN",
  "code": "4030",
  "message": "접근 권한이 없습니다.",
  "path": "/api/courses/4/students/9"
}
```

---

## 8) 코드 예제 (axios)

### 수강생 목록

```javascript
async function fetchStudents(courseId, page = 0, size = 20) {
  const { data } = await axios.get(
    `/api/courses/${courseId}/students`,
    { params: { page, size, sort: 'createdAt,desc' } }
  );
  return data; // PageResponse<CourseStudentItemDto>
}
```

### 수강생 제거

```javascript
async function removeStudent(courseId, studentId) {
  await axios.delete(`/api/courses/${courseId}/students/${studentId}`);
  // 204
}
```

### 수강생 차단

```javascript
async function blockStudent(courseId, studentId) {
  await axios.post(`/api/courses/${courseId}/students/${studentId}/block`);
  // 204
}
```

### 일괄 승인

```javascript
async function approveBulk(courseId, requestIds) {
  const { data } = await axios.post(
    `/api/courses/${courseId}/join-requests/bulk/approve`,
    { requestIds }
  );
  // data = { successCount, failureCount, results: [...] }
  return data;
}
```

---

## 9) 점검 체크리스트

- [ ] 가입 요청 응답의 `requestedAt` 파싱이 `Z` 접미사 포함 형태에서도 정상 동작
- [ ] 교사 강의실 상세에 "수강생" 탭 추가 (목록 + 제거/차단 버튼)
- [ ] 제거/차단 모두 확인 모달 표시 (특히 차단은 영구)
- [ ] 가입 요청 탭에 다중 선택 + 일괄 승인/거절 버튼
- [ ] 일괄 처리 결과 모달에서 부분 실패(failureCount > 0) UX 처리
- [ ] 알림(`COURSE_MEMBER_REMOVED` / `COURSE_MEMBER_BLOCKED`) 라우팅이 강의실 화면으로 연결
- [ ] 에러 코드 신규 매핑 적용 (`4041`, `4030` 메시지)

---

## 10) 다음 라운드 예고

- 강의실 활동 감사 로그(STUDENT_REMOVED / STUDENT_BLOCKED / STUDENT_REJOINED 등)는 현재 SLF4J 로 적재만 되고 별도 조회 API 가 없다. 운영 화면(어드민) 요구가 생기면 별도 라운드에서 DB 영속화 + 조회 API 검토.
- 일괄 처리에서 errorCode 를 enum 이름(예: `JOIN_REQUEST_ALREADY_PROCESSED`)으로도 함께 노출할지 여부는 FE 사용감 보고 결정.
