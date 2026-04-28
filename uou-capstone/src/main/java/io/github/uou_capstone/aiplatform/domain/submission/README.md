# submission 도메인

v1 답안 제출 흐름 (`Submission` + `StudentAnswer`). `assessment` 도메인의 `Assessment` 와 짝.
이 도메인 작업 시 참고용 상시 메모. 버그·성능 수정 **이력** 은 루트 `uou-capstone/DEV_NOTES.md` 참조.

> ⚠️ **신규 시험 응시는 `exam` 도메인 (`POST /api/exams/submission`) 사용**. 이 도메인은 v1 정형 평가(Assessment) 응시 컨테이너 — 유지보수 우선.

---

## 개요

- **Submission**: 학생-평가 1쌍에 대한 응시 1건. `(student, assessment)` 사실상 유니크 (Repository 의 `existsByStudentAndAssessment` 로 가드). `examResult` nullable 참조 (v2 채점 결과 연결고리)
- **StudentAnswer**: 문제별 답안 1행. 객관식(`choiceOption`) 또는 서술형(`descriptiveAnswer`) 중 하나. 채점 후 `isCorrect`, `score`, `teacherComment`, AI 피드백(`feedbackJson`), 채점 메타(`evaluationMetadata`) 채워짐
- **SubmissionStatus**: `SUBMITTED` (제출 직후) / `GRADED` (채점 완료) — 현재 `SUBMITTED` 만 자동 설정, `GRADED` 전이 코드 없음

---

## 주요 플로우

prefix: `/api`

| 메서드/경로 | 권한 | 용도 |
|---|---|---|
| `POST /assessments/{assessmentId}/submissions` | STUDENT | 답안 제출 — Submission + StudentAnswer N개 일괄 저장 |
| `GET  /submissions/{submissionId}` | STUDENT | 본인 제출 결과 상세 (소유자만) |
| `GET  /assessments/{assessmentId}/submissions` | TEACHER | 평가별 제출 현황 목록 (강의실 소유 교사만) |

---

## 상시 주의사항 (Gotcha)

### 중복 제출 가드
`submissionRepository.existsByStudentAndAssessment(student, assessment)` 로 가드. **DB 유니크 제약이 아닌 애플리케이션 레벨** — race condition 시 동시 제출 가능. 현재 사용량에서 충돌 빈도 낮아 허용.

### 채점 흐름 미연결
- 현재 `createSubmission` 만 존재 — 자동 채점·교사 채점 호출 경로 없음
- `StudentAnswer.{isCorrect,score,teacherComment,feedbackJson,evaluationMetadata}` 는 모두 nullable 로 비어 있는 상태 저장
- v2 흐름은 `exam` 도메인의 `ExamGradingService` 가 `ExamResult` 에 직접 기록 — 이 도메인을 거치지 않음

### v2 ExamQuestion 사용
StudentAnswer 의 question 필드는 `assessment.entity.Question` (옛 v1 엔티티) 가 아닌 **`exam.entity.ExamQuestion`**. 이 도메인 안에서 `exam.entity.ExamQuestion` import — cross-domain 의존 명시.
```java
StudentAnswer.question : exam.entity.ExamQuestion
StudentAnswer.choiceOption : assessment.entity.ChoiceOption
```

### 권한 체크 — Controller `@PreAuthorize` + Service 검증 이중
- `createSubmission`: `@PreAuthorize("hasAuthority('STUDENT')")` + `enrollmentRepository.existsByStudentAndCourse` 추가 검증 (수강생만)
- `getSubmissionResult`: 컨트롤러는 STUDENT 만 통과시키고, 서비스에서 `submission.getStudent().getUser().getId()` 비교로 본인 검증
- `getSubmissionsForAssessment`: TEACHER + 강의실 소유자만

### N+1 가능성 — 답안 조회 시
`getSubmissionResult` → `studentAnswerRepository.findBySubmissionId` 후 DTO 변환에서 `answer.getQuestion()`, `answer.getChoiceOption()` 접근. 둘 다 lazy → **N개 답안마다 question/option 추가 쿼리**. 답안 많아지면 fetch join 추가 필요.

### v2 연결고리: `Submission.examResult`
nullable. v2 흐름과 통합 시 채점 완료된 v1 Submission 에 v2 ExamResult 를 연결하는 용도. 현재 호출 경로 없음 (`updateExamResult` 메서드만 존재).

### 강의실/평가 cascade 삭제 누락
- `Assessment` 삭제 시 `Submission` cascade 가 **걸려 있지 않음** (Assessment 엔티티에 `OneToMany Submission` 정의 X)
- 강의실 삭제 흐름(`CourseService.deleteCourse`)에도 submission 정리 코드 없음 → **고아 row 가능성**
- 현재 사용량에서 문제 안 됨. 정합성 강화 시 `submissionRepository.deleteByAssessment_Course_Id(courseId)` 같은 정리 메서드 추가 필요

### 컨트롤러 prefix 가 `/api` (모든 자식 경로 fully-qualified)
다른 도메인은 `/api/xxx` prefix 단일이지만, 이 도메인은 컨트롤러 RequestMapping 이 `/api` 라 메서드별로 `/assessments/...`, `/submissions/...` 두 패턴이 공존. 의도된 — 평가 기준/제출 기준 두 시점에서 접근.

---

## 주요 파일

### Controller
- `controller/SubmissionController.java` — 제출/조회/평가별 현황

### Service
- `service/SubmissionService.java` — Submission + StudentAnswer 일괄 저장, 권한 검증

### Repository
- `repository/SubmissionRepository.java` — `existsByStudentAndAssessment`, `findByAssessmentId`
- `repository/StudentAnswerRepository.java` — `findBySubmissionId`

### Entity
- `entity/Submission.java` — `assessment` + `student` + nullable `examResult` (v2 연결)
- `entity/StudentAnswer.java` — 객관식/서술형 답안 + 채점 결과 + JSON 피드백
- `entity/SubmissionStatus.java` — SUBMITTED / GRADED (전이 미구현)

### DTO
- `dto/SubmissionRequestDto.java`, `StudentAnswerRequestDto.java` — 일괄 제출용
- `dto/SubmissionResponseDto.java`, `StudentAnswerResponseDto.java` — 상세 응답
- `dto/SubmissionStatusDto.java` — 교사용 제출 현황 요약

### 의존 (다른 도메인)
- `assessment.entity.{Assessment, ChoiceOption}`, `assessment.repository.{AssessmentRepository, ChoiceOptionRepository}`
- `exam.entity.{ExamQuestion, ExamResult}`, `exam.repository.ExamQuestionRepository` — v2 연결
- `course.repository.EnrollmentRepository` — 수강생 권한 체크
- `user.entity.{Student, Teacher, User}` — 권한 체크
