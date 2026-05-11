# course/notice 도메인

강의실 공지사항. 교사가 작성하면 ACTIVE 수강생 전체에 알림이 발송된다. 댓글은 학생도 작성 가능하며 1단계 답글까지 허용.
이 도메인 작업 시 참고용 상시 메모.

---

## 개요

- **Notice 엔티티**: `course` (FK), `author` (FK Teacher), `title` (100자), `contentMarkdown` (TEXT, 12000자 검증), `category`, `priority`, `pinned`. 자식 컬렉션 `comments` (cascade=ALL, orphanRemoval=true)
- **NoticeComment 엔티티**: `notice`, `author` (User — 학생/교사 모두), `parentComment` (self-ref, nullable), `contentMarkdown` (4000자). 1단계 답글만
- **NoticeCategory**: `GENERAL` / `EXAM` / `MATERIAL` / `ASSIGNMENT`. 기본값 `GENERAL`
- **NoticePriority**: `NORMAL` / `IMPORTANT`. 기본값 `NORMAL`
- 역할:
  - **TEACHER**: 공지 작성/수정/삭제 (작성자 본인만 수정, 작성자 또는 강의실 교사가 삭제)
  - **STUDENT (ACTIVE)**: 조회 + 댓글
  - **공통**: 댓글 수정은 작성자, 삭제는 작성자 또는 강의실 교사

---

## 주요 플로우

prefix: `/api/courses/{courseId}/notices`

| 메서드/경로 | 권한 | 용도 |
|---|---|---|
| `GET .` | TEACHER+STUDENT | 목록 (`PageResponse`, 정렬 `pinned DESC, createdAt DESC`) |
| `POST .` | TEACHER | 공지 작성 → ACTIVE 수강생에 `NOTICE_PUBLISHED` 알림 |
| `GET ./{noticeId}` | TEACHER+STUDENT | 상세 |
| `PATCH ./{noticeId}` | TEACHER (작성자) | 부분 수정 |
| `DELETE ./{noticeId}` | TEACHER (작성자 OR 강의실 교사) | 삭제 (cascade로 댓글까지) |
| `GET ./{noticeId}/comments` | TEACHER+STUDENT | 댓글 페이지 (`createdAt ASC`) |
| `POST ./{noticeId}/comments` | TEACHER+STUDENT | 댓글 작성. `parentCommentId` 있으면 답글 |
| `PATCH .../comments/{commentId}` | 작성자 | 댓글 수정 |
| `DELETE .../comments/{commentId}` | 작성자 OR 강의실 교사 | 댓글 삭제 |

---

## 권한 체크

모두 `CourseAccessService` (course/service/) 경유. 직접 `currentUserResolver.getTeacher()` 호출 금지 — 학생 사용자에 대해 `MEMBER_NOT_FOUND` 가 떨어져 의미상 `FORBIDDEN` 과 일치하지 않는다.

- 작성/수정/삭제: `loadCourseAsTeacher(courseId)` — 본인 강의실 교사 보장
- 조회/댓글: `loadCourseAsParticipant(courseId)` — 교사 또는 ACTIVE 수강생 보장
- 댓글 수정: `ensureAuthor(currentUser, comment.author.id)` — 작성자 본인만
- 공지 수정: `ensureAuthor(currentUser, notice.author.user.id)` — **`Notice.author` 가 Teacher 라 `getUser().getId()` 로 변환해서 비교**
- 댓글 삭제: `ensureAuthorOrCourseTeacher(currentUser, course, comment.author.id)`

---

## 1단계 답글 제약 + parent 범위 검증

`NoticeCommentService.createComment()`:
1. `parentCommentId` 가 있으면 **반드시** `noticeCommentRepository.findByIdAndNotice(parentId, notice)` 로 조회 — `findById` 만 쓰면 다른 게시글 댓글을 parent 로 거는 케이스를 막을 수 없다
2. 조회 결과 `parent.getParentComment() != null` 이면 `INVALID_PARAMETER` ("대댓글의 답글은 허용되지 않습니다.")

---

## 알림

`NotificationService.notify(...)` 직접 호출. 트랜잭션 커밋 후 SSE push 는 `NotificationService` 가 처리.

| 시점 | NotificationType | 대상 | resourceType | resourceId |
|---|---|---|---|---|
| 공지 작성 | `NOTICE_PUBLISHED` | ACTIVE 수강생 전체 | `"NOTICE"` | `notice.id` |
| 댓글 답글 | `NOTICE_COMMENT_REPLIED` | parent 댓글 작성자 (본인 제외) | `"NOTICE"` | `notice.id` |

`body` 는 `currentUser.getFullName() + ": " + summary`. `summary` 는 `NotificationBodyFormatter.summarize(text, 100)` 로 100자 truncate.

---

## 상시 주의사항 (Gotcha)

### Course 에 역참조 컬렉션 추가 금지
`Course` 엔티티에 `notices` `OneToMany` 컬렉션을 **추가하지 말 것**. cascade 영향면이 광범위해진다. 강의실 삭제 시 notices/notice_comments 정리는 V2 DDL 의 `ON DELETE CASCADE` 로 처리.

### 댓글 cascade — `@OnDelete` 가 핵심
`NoticeComment.parentComment` 와 `NoticeComment.notice` 매핑에 `@OnDelete(action = CASCADE)` 가 붙어 있다. 이게 없으면 H2 (`ddl-auto: create-drop`, Flyway 비활성화) 테스트 환경에서 부모 댓글 삭제 시 자식 답글이 살아남아 운영 MySQL 과 동작이 어긋난다.

테스트는 `repository.delete(parent); em.flush(); em.clear();` 로 1차 캐시 비우고 재조회해야 cascade 가 검증된다 (`@OnDelete` 는 DDL 단계 cascade 만 보장 — JPA 영속 컨텍스트 cascade 가 아님).

### `Notice.author` 는 Teacher
공지는 교사만 작성 가능 → `author` 필드는 `Teacher` 엔티티. PATCH 권한 비교 시 반드시 `notice.getAuthor().getUser().getId()` 로 User.id 를 추출해서 `currentUser.getId()` 와 비교. `notice.getAuthor().getId()` (= teacher_id) 와 비교하면 항상 false.

### `NotificationType` 칼럼은 varchar
`notifications.type` 은 `@Enumerated(STRING)` 으로 `varchar(40)`. 신규 enum 추가 시 DDL 변경 불필요 — Java enum 만 추가하면 됨.

### Sort whitelist 에 `pinned` 포함 필수
기본 정렬이 `pinned DESC, createdAt DESC` 라 `PageableSupport.validate` 의 sort whitelist 에 반드시 `pinned` 가 들어가야 한다. 빠지면 INVALID_PARAMETER.

### DTO 의 boolean 은 wrapper 타입
`pinned` 등은 `Boolean` (wrapper). PATCH 에서 "그대로 둠" vs "false 로 변경" 을 구분하려면 null 식별이 필요하다. primitive `boolean` 금지.

---

## 주요 파일

### Controller
- `controller/NoticeController.java` — 공지 CRUD
- `controller/NoticeCommentController.java` — 공지 댓글 CRUD

### Service
- `service/NoticeService.java` — 공지 CRUD + 알림 발송 (생성 시 ACTIVE 수강생 전체)
- `service/NoticeCommentService.java` — 댓글 CRUD + 1단계 답글 제약 + 답글 알림

### Repository
- `repository/NoticeRepository.java` — `findByCourse(Pageable)`, `findByIdAndCourse`
- `repository/NoticeCommentRepository.java` — `findByNotice(Pageable)`, `findByIdAndNotice` (parent 범위 검증용)

### Entity
- `entity/Notice.java` — `course`, `author(Teacher)`, `title`, `contentMarkdown(TEXT)`, `category`, `priority`, `pinned`, `comments`
- `entity/NoticeComment.java` — `notice`, `author(User)`, `parentComment(self)`, `contentMarkdown(TEXT)`. `@OnDelete(CASCADE)` 양쪽 FK 에 명시
- `entity/NoticeCategory.java`, `entity/NoticePriority.java`

### DTO
- `dto/NoticeCreateRequestDto`, `NoticeUpdateRequestDto`, `NoticeResponseDto`, `NoticeListItemResponseDto`
- `dto/NoticeCommentCreateRequestDto`, `NoticeCommentUpdateRequestDto`, `NoticeCommentResponseDto`

### 의존 (다른 도메인)
- `course.service.CourseAccessService` — 공통 권한 헬퍼
- `course.repository.EnrollmentRepository.findByCourseAndStatusWithStudentUser` — 알림 대상 수강생 조회 (JOIN FETCH)
- `notification.service.NotificationService.notify` — 알림 발송
- `common.util.NotificationBodyFormatter.summarize` — 댓글 알림 body 100자 truncate

### 참고
- 마이그레이션: `src/main/resources/db/migration/V2__course_interaction_features.sql` (notices / notice_comments 포함)
- NotificationType 신규 값: `NOTICE_PUBLISHED`, `NOTICE_COMMENT_REPLIED`
