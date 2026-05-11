# course/discussion 도메인

토론·자유게시판. 학생도 작성 가능. 1단계 답글까지. 상세 조회 시 viewCount +1.

---

## 개요

- **Discussion 엔티티**: `course`, `author` (User — 학생/교사 모두), `title` (120자), `contentMarkdown` (TEXT, 12000자), `category`, `pinned`, `allowComments`, `viewCount`. 자식 `comments` (cascade=ALL, orphanRemoval=true)
- **DiscussionComment 엔티티**: `discussion`, `author` (User), `parentComment` (self-ref), `contentMarkdown`. 1단계 답글만
- **DiscussionCategory**: `QUESTION` / `FREE` / `RESOURCE`. 기본값 `FREE`
- **`allowComments`**: 작성자가 댓글 비활성 가능. false 면 `INVALID_PARAMETER`
- 역할:
  - **TEACHER+STUDENT (참가자)**: 게시글 작성 (ACTIVE 수강생만)
  - **작성자**: 수정
  - **작성자 OR 강의실 교사**: 삭제 (스팸/규정 위반 게시글 정리용)

---

## 주요 플로우

prefix: `/api/courses/{courseId}/discussions`

| 메서드/경로 | 권한 | 용도 |
|---|---|---|
| `GET .` | TEACHER+STUDENT | 목록 (`PageResponse`, `pinned DESC, createdAt DESC`) |
| `POST .` | TEACHER+STUDENT (참가자) | 게시글 작성 |
| `GET ./{discussionId}` | TEACHER+STUDENT | 상세 — **viewCount +1** |
| `PATCH ./{discussionId}` | 작성자 | 부분 수정 |
| `DELETE ./{discussionId}` | 작성자 OR 강의실 교사 | 삭제 (cascade로 댓글까지) |
| `GET ./{discussionId}/comments` | TEACHER+STUDENT | 댓글 페이지 |
| `POST ./{discussionId}/comments` | TEACHER+STUDENT (참가자) | 댓글 작성 (allowComments=true 일 때만) |
| `PATCH .../comments/{commentId}` | 작성자 | 댓글 수정 |
| `DELETE .../comments/{commentId}` | 작성자 OR 강의실 교사 | 댓글 삭제 |

---

## 권한 체크

`CourseAccessService` 경유.
- 모든 진입점: `loadCourseAsParticipant(courseId)` — 강의실 교사 또는 ACTIVE 수강생
- 게시글 수정: `ensureAuthor(currentUser, discussion.author.id)` — `author` 가 User 라 그대로 비교
- 게시글 삭제: `ensureAuthorOrCourseTeacher(currentUser, course, discussion.author.id)`

---

## viewCount 처리 (1차 정책)

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("UPDATE Discussion d SET d.viewCount = d.viewCount + 1 WHERE d.id = :id")
void incrementViewCount(@Param("id") Long id);
```

같은 사용자 반복 조회로도 +1 됨. 24시간 디바운스(`DiscussionViewReceipt`) 는 후속 PR.

`getDiscussion` 흐름: 존재 검증 → `incrementViewCount(id)` → `clearAutomatically=true` 가 1차 캐시 비움 → 재조회로 최신 viewCount 반환.

---

## 알림 (`DISCUSSION_COMMENT_RECEIVED`)

`DiscussionCommentService.createComment()` — 본인이 본인 글/댓글에 댓글 다는 경우는 알림 스킵.

| 시점 | 대상 |
|---|---|
| 일반 댓글 (parentCommentId 없음) | `discussion.getAuthor()` (게시글 작성자) |
| 답글 (parentCommentId 있음) | `parent.getAuthor()` (parent 댓글 작성자) |

`title` 분기:
- 일반 댓글 → "내 게시글에 댓글"
- 답글 → "내 댓글에 답글"

`body`: `currentUser.getFullName() + ": " + summarize(text, 100)`. `resourceType="DISCUSSION"`, `resourceId=discussion.id`.

> 레퍼런스 초안에서 `post.author.user` 라고 잘못 적힌 부분이 있었지만 `Discussion.author` 자체가 User 다. `discussion.getAuthor()` 그대로 사용.

---

## 1단계 답글 제약

`commentRepository.findByIdAndDiscussion(parentId, discussion)` 으로 같은 게시글 댓글인지 검증 (다른 게시글 댓글 ID 차단). 조회된 parent 의 `parentComment != null` 이면 `INVALID_PARAMETER`.

---

## 상시 주의사항 (Gotcha)

### `allowComments=false` 검증 위치
서비스 진입에서 검증. 컨트롤러 `@PreAuthorize` 만으로는 차단 불가능.

### Course 역참조 컬렉션 추가 금지
notice 와 동일. `Course` 에 `discussions` `OneToMany` 추가 금지.

### 댓글 cascade
`@OnDelete(action = CASCADE)` 가 `parentComment` 와 `discussion` FK 양쪽에 붙어 있어야 H2 단위 테스트 / 운영 MySQL 모두에서 부모 삭제 → 자식 자동 삭제가 보장된다.

### viewCount 동시성
`@Modifying UPDATE ... viewCount + 1` 은 단일 atomic UPDATE 라 동시 호출 시에도 정확. 다만 같은 트랜잭션 안에서 같은 row 를 두 번 incrementViewCount 호출하면 `clearAutomatically` 로 1차 캐시가 비워져도 두 번째 호출은 fresh row 에서 +1 되므로 일관성 유지.

### Sort whitelist `pinned` 포함
notice 와 동일. `Set.of("createdAt", "updatedAt", "pinned")`.

### DTO Boolean wrapper
`pinned`, `allowComments` 모두 `Boolean` (null=그대로 유지).

---

## 주요 파일

### Controller
- `controller/DiscussionController.java`
- `controller/DiscussionCommentController.java`

### Service
- `service/DiscussionService.java` — 게시글 CRUD + viewCount 증가
- `service/DiscussionCommentService.java` — 댓글 CRUD + allowComments 검증 + 알림

### Repository
- `repository/DiscussionRepository.java` — `findByCourse(Pageable)`, `findByIdAndCourse`, `incrementViewCount` (@Modifying)
- `repository/DiscussionCommentRepository.java` — `findByDiscussion(Pageable)`, `findByIdAndDiscussion`

### Entity
- `entity/Discussion.java` — `author(User)`, `viewCount`, `allowComments`, `pinned`, ...
- `entity/DiscussionComment.java` — `@OnDelete(CASCADE)` 양쪽 FK
- `entity/DiscussionCategory.java`

### DTO
- `dto/DiscussionCreateRequestDto`, `DiscussionUpdateRequestDto`, `DiscussionResponseDto`, `DiscussionListItemResponseDto`
- `dto/DiscussionCommentCreateRequestDto`, `DiscussionCommentUpdateRequestDto`, `DiscussionCommentResponseDto`

### 의존
- `course.service.CourseAccessService`
- `notification.service.NotificationService.notify`
- `common.util.NotificationBodyFormatter.summarize`

### 참고
- 마이그레이션: `V2__course_interaction_features.sql` (discussions / discussion_comments 포함)
- NotificationType 신규: `DISCUSSION_COMMENT_RECEIVED` (답글·일반 댓글 모두 동일 enum, title 로 분기)
