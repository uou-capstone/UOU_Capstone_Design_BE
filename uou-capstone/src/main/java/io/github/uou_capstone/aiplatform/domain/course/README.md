# course 도메인

강의실(`Course`) + 수강 등록(`Enrollment`) 관리. 하위 패키지 `lecture/` 는 별도 README (`course/lecture/README.md`).
이 도메인 작업 시 참고용 상시 메모. 버그·성능 수정 **이력** 은 루트 `uou-capstone/DEV_NOTES.md` 참조.

---

## 개요

- **Course 엔티티**: 한 명의 `Teacher` 가 소유. `title`, `description`, `invitationCode` (UUID, 유니크). 자식: `lectures`, `assessments`, `enrollments` (모두 `cascade=ALL`, `orphanRemoval=true`)
- **Enrollment 엔티티**: `(student, course)` 유니크. `EnrollmentStatus` (`ACTIVE`/`COMPLETED`/`DROPPED`) — 현재 항상 `ACTIVE` 로 생성, 상태 전이 미구현
- 역할:
  - **TEACHER**: 강의실 생성/수정/삭제, 자료·시험 일괄 삭제
  - **STUDENT**: 강의실 입장 (ID/초대코드), 본인 수강 목록 조회
  - **공통**: 상세 + 주차별 자료(`/contents`) 조회

---

## 주요 플로우

prefix: `/api/courses`

| 메서드/경로 | 권한 | 용도 |
|---|---|---|
| `POST .` | TEACHER | 강의실 생성 — `invitationCode` UUID 자동 발급 |
| `GET .` | TEACHER/STUDENT | 강의실 목록 — 역할별 분기 (TEACHER: 본인 소유 최신순 / STUDENT: 수강중 최신순) |
| `GET ./{courseId}` | TEACHER/STUDENT | 강의실 상세 |
| `GET ./{courseId}/contents` | TEACHER/STUDENT | **주차별** 강의자료 + 시험세션 통합 조회 (N+1 방지 — 아래 Gotcha) |
| `POST ./{courseId}/contents/delete` | TEACHER | 자료/시험세션/생성세션 ID 리스트 일괄 삭제 |
| `PUT ./{courseId}` | TEACHER | 제목·설명 수정 |
| `DELETE ./{courseId}` | TEACHER | 강의실 삭제 + 자식 정리 |
| `POST ./{courseId}/enroll` | STUDENT | 강의실 ID 기반 수강 신청 (구버전, 유지) |
| `POST ./join?code=` | STUDENT | 초대코드로 입장 — 권장 경로 |

---

## 상시 주의사항 (Gotcha)

### `getCourseContents` 의 N+1 방지 — 벌크 IN 조회 패턴
강의 N개에 대해 자료/시험을 각각 N번 조회하지 말 것. 다음 두 메서드로 **한 번에** 가져와 `Map<Long, List<X>>` 로 그룹핑:
```java
materialRepository.findByLecture_IdInOrderByLecture_IdAscCreatedAtDesc(lectureIds)
examSessionRepository.findByLecture_IdIn(lectureIds)
```
강의 자체는 `findByIdWithLectures` (LEFT JOIN FETCH) 로 한 쿼리. 새 콘텐츠 종류 추가 시 동일 패턴 사용.

### 강의실 삭제 자식 정리 순서
FK 제약 위반 방지. **이 순서를 지킬 것**:
```java
generatedContentRepository.clearSessionByCourseId(courseId);    // GenerationSession FK 참조 해제
generationSessionRepository.deleteByLectureCourseId(courseId);
examSessionRepository.deleteByLectureCourseId(courseId);
examProfileRepository.deleteByLectureCourseId(courseId);
materialRepository.deleteByLectureCourseId(courseId);
courseRepository.delete(course);                                  // cascade: lectures → generated_contents, student_inquiries
```
- `clearSessionByCourseId` 는 네이티브 UPDATE — JPA 의 SET 컬럼 모호성 회피
- 단건 강의 삭제 시 순서는 `course/lecture/README.md` 참조 (lecture-scope 변형)

### 초대코드는 UUID
`UUID.randomUUID().toString()` — 36자 길이. 짧은 코드(6자리 등) 정책으로 바뀌면 DB `unique` 제약 + 충돌 재시도 로직 필요. 현재는 충돌 가능성이 사실상 0 이라 단순 구조.

### `existsByStudentAndCourse` 는 권한 체크 전반에 사용됨
`material`, `exam`, `course/lecture` 등 여러 도메인의 STUDENT 접근 검증이 이 메서드를 호출. 시그니처 변경하면 광범위 영향.

### `Enrollment.status` 미사용
현재 코드는 항상 `ACTIVE` 로 생성하고 전이 메서드가 없음. 수강 취소·완료 처리 추가 시 `EnrollmentService` 에 상태 전이 메서드 + Repository 쿼리 분기 필요 (`existsByStudentAndCourseAndStatus(...)` 등).

### `getAllCourses` 의 ADMIN/그 외 역할
`Role.TEACHER` / `Role.STUDENT` 외 역할이 들어오면 `findAll()` (전체) 반환. 새 역할 추가 시 의도치 않은 정보 노출 가능성 — 분기 보강 필요.

### `deleteCourseContents` 는 도메인 서비스에 위임
- Material → `materialService.deleteMaterial`
- ExamSession → `examGenerationService.deleteExamSession`
- GenerationSession → `materialGenerationService.deleteGenerationSession`

각 도메인의 권한 체크가 두 번 일어남(course-level + 도메인-level). 의도된 방어 — 우회하지 말 것.

---

## 주요 파일

### Controller
- `controller/CourseController.java` — 강의실 CRUD + 수강 신청 + contents 조회/삭제

### Service
- `service/CourseService.java` (266줄) — 강의실 CRUD, contents 집계, 자식 정리 + 도메인 위임 삭제
- `service/EnrollmentService.java` (70줄) — ID 기반 / 초대코드 기반 수강 신청

### Repository
- `repository/CourseRepository.java` — `findByTeacherOrderByCreatedAtDesc`, `findByInvitationCode`, `existsByInvitationCode`, `findByIdWithLectures` (LEFT JOIN FETCH)
- `repository/EnrollmentRepository.java` — `existsByStudentAndCourse` (권한 체크 핵심), `findByStudent`

### Entity
- `entity/Course.java` — `teacher`, `title`, `description`, `invitationCode`(UUID), 자식 컬렉션 3종
- `entity/Enrollment.java` — `(student, course)` 유니크
- `entity/EnrollmentStatus.java` — ACTIVE/COMPLETED/DROPPED (현재 ACTIVE 만 사용)

### DTO
- `dto/CourseCreateRequestDto.java`, `CourseUpdateRequestDto.java`, `CourseResponseDto.java`
- `dto/CourseContentsResponseDto.java`, `LectureContentsDto.java`, `MaterialSummaryDto.java`, `ExamSessionSummaryDto.java` — 주차별 contents 응답
- `dto/CourseContentsDeleteRequestDto.java` — `materialIds` / `examSessionIds` / `generationSessionIds` 묶어서

### 의존 (다른 도메인)
- `material.service.MaterialService` — 자료 삭제 위임
- `material.generation.service.MaterialGenerationService` — 생성 세션 삭제 위임
- `exam.service.ExamGenerationService` — 시험 세션 삭제 위임
- `exam.repository.{ExamSessionRepository, ExamProfileRepository}` — 강의실 삭제 시 cascade
- `material.repository.{MaterialRepository, GenerationSessionRepository}` — 동
- `course.lecture.repository.GeneratedContentRepository` — `clearSessionByCourseId`
- `user.entity.{Student, Teacher, User, Role}` — 권한 체크
