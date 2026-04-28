# assessment 도메인

v1 정형 평가(`Assessment`) + 객관식 선택지(`ChoiceOption`). 강의실(Course) 단위로 묶이는 **퀴즈/과제 컨테이너**.
이 도메인 작업 시 참고용 상시 메모. 버그·성능 수정 **이력** 은 루트 `uou-capstone/DEV_NOTES.md` 참조.

> ⚠️ **신규 시험 기능은 `exam` 도메인 (`ExamSession` 기반 v2)에 추가**. 이 도메인은 **v1 호환 + 정형 평가 컨테이너 역할** 만 유지.

---

## 개요

- **Assessment 엔티티**: 한 강의실의 평가 묶음. `course` (FK), `title`, `dueDate`, `type` (`AssessmentType.QUIZ`/`ASSIGNMENT`), `aiGeneratedStatus` (PENDING/PROCESSING/COMPLETED/FAILED), `examSession` 선택 참조 (v2 연결고리)
- **ChoiceOption 엔티티**: 객관식 선택지. `question` (`exam.entity.ExamQuestion`) + `text` + `isCorrect`
- **CreatedBy** Enum: `TEACHER` / `AI`
- 역할: **TEACHER** 가 생성, **TEACHER/STUDENT** 모두 조회 (수강자/소유자만)

핵심 관계도
```
Course 1 ── * Assessment
              │
              └── * ExamQuestion (in exam 도메인)
                       │
                       └── * ChoiceOption (in assessment 도메인)
```
> `ExamQuestion` 은 `exam.entity` 에 있지만 `Assessment.questions` 의 cascade 대상 — 어색한 도메인 분할이지만 역사적 배경. **`ExamQuestion` 직접 수정·삭제는 `assessment` cascade 경로로** 일어남.

---

## 주요 플로우

prefix: `/api/assessments`

| 메서드/경로 | 권한 | 용도 |
|---|---|---|
| `POST /courses/{courseId}` | TEACHER | 평가 생성 — Assessment + ExamQuestion + ChoiceOption 일괄 저장 |
| `GET  /courses/{courseId}` | TEACHER/STUDENT | 강의실 평가 목록 (수강자/소유자만) |
| `GET  /{assessmentId}` | TEACHER/STUDENT | 평가 상세 — Fetch Join 으로 questions 1쿼리 (옵션은 lazy) |

---

## 상시 주의사항 (Gotcha)

### 평가 생성 시 권한 체크 누락
`createAssessment` 에 **course 소유자 검증 코드가 빠져 있음** (`// (권한 확인 로직)` 주석만 남음). 현재는 `@PreAuthorize("hasAuthority('TEACHER')")` 로 역할만 검증 → **다른 강의실 소유 교사가 임의 강의실에 평가를 추가할 수 있는 상태**. 보안 강화 시 다음을 추가:
```java
Teacher currentTeacher = currentUserResolver.getTeacher();
if (!course.getTeacher().getId().equals(currentTeacher.getId())) {
    throw new BusinessException(CommonErrorCode.FORBIDDEN);
}
```

### N+1 — `findByIdWithQuestions` 만 fetch, 옵션은 추가 쿼리
```java
@Query("SELECT DISTINCT a FROM Assessment a LEFT JOIN FETCH a.questions q WHERE a.id = :assessmentId")
```
**questions 까지만** fetch. `ChoiceOption` 은 lazy → 상세 응답 변환 시 N개 추가 쿼리. 객관식이 많으면 `LEFT JOIN FETCH q.choiceOptions` 추가 고려 (단 `MultipleBagFetchException` 회피를 위해 collection 1개 더 추가 시 EntityGraph 또는 batch fetch 로 분리 필요).

### `ExamQuestion` 빌더에 v1 호환 NULL 인자
```java
ExamQuestion.builder()
    .examSession(null)        // v1 호환
    .questionOrder(null)      // v1 호환
    .questionMetadata(null)   // v1 호환
    ...
```
이 도메인에서 만든 `ExamQuestion` 은 **`examSession` 이 항상 null**. v2 시험 흐름은 별도. `ExamQuestion.examSession` nullable 제약 변경 시 이 경로 깨짐.

### `ChoiceOption.question` 은 `exam.entity.ExamQuestion`
`assessment` 도메인 안에서 `exam.entity.ExamQuestion` import — **양방향 의존**. exam 도메인 패키지 이동·이름 변경 시 동반 수정.

### `Assessment.aiGeneratedStatus` 는 흐름 진입점이 없음
필드는 있으나 PENDING 외 다른 값으로 갱신하는 호출 경로가 현재 코드에 없음. AI 자동 평가 생성 흐름 도입 시 사용 예정 — 현재는 항상 `PENDING`.

### 강의실 cascade 삭제 흐름
`Course.assessments` → `cascade=ALL` → `Assessment` 자동 삭제, 그 안의 `questions` (ExamQuestion) 도 cascade. 단 `course/CourseService.deleteCourse` 는 자식 정리 순서를 명시적으로 수행 — assessment 측 별도 정리 메서드 없음 (필요 없음).

### `ChoiceOptionRepository` 가 비어 있음
현재 별도 쿼리 메서드 없음. `JpaRepository` 기본 + cascade 로 충분. 옵션만 따로 조회/검색 필요 시 추가.

### `findByCourse_Id` 명명 — 그대로 유지
Spring Data underscore 표기. `findByCourseId` 로 바꾸면 Course.id 가 아닌 `Assessment.courseId` (없는 필드) 로 인식 시도 → 깨짐.

---

## 주요 파일

### Controller
- `controller/AssessmentController.java` — 평가 생성/목록/상세

### Service
- `service/AssessmentService.java` — Assessment + Question + Option 일괄 저장, fetch join 상세

### Repository
- `repository/AssessmentRepository.java` — `findByCourse_Id`, `findByIdWithQuestions` (LEFT JOIN FETCH)
- `repository/ChoiceOptionRepository.java` — 기본 JPA (커스텀 X)

### Entity
- `entity/Assessment.java` — `course` 소속, `examSession` 선택 참조 (v2 연결), `questions` cascade
- `entity/ChoiceOption.java` — `exam.entity.ExamQuestion` 의 옵션 (객관식)
- `entity/AssessmentType.java` — QUIZ / ASSIGNMENT
- `entity/CreatedBy.java` — TEACHER / AI

### DTO
- `dto/AssessmentCreateRequestDto.java`, `QuestionCreateDto.java`, `ChoiceOptionCreateDto.java` — 일괄 생성용
- `dto/AssessmentSimpleDto.java`, `AssessmentDetailDto.java`, `QuestionResponseDto.java`, `OptionResponseDto.java` — 응답용

### 의존 (다른 도메인)
- `course.entity.Course`, `course.repository.CourseRepository`, `course.repository.EnrollmentRepository` — 권한 체크
- `exam.entity.ExamQuestion`, `exam.repository.ExamQuestionRepository` — 문제 저장 (cross-domain)
- `course.lecture.entity.AiGeneratedStatus` — 상태 enum 재사용
