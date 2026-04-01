# 개발 노트 — 문제 발견 & 수정 이력

> 발견한 버그·성능 문제와 그 원인 추적 과정, 수정 방법을 기록한다.

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

- “어느 API가 실제로 405를 내는지”를 즉시 특정 가능
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
