# course/attendance 도메인

명시 출석. 회차 단위 + 학생별 record. 회차 생성 시 ACTIVE 수강생 전체에 ABSENT record 가 자동 생성된다.

---

## 개요

- **AttendanceSession 엔티티**: `course`, `lecture` (nullable — 독립 회차 허용), `title`, `sessionDate`, `startTime`/`endTime` (nullable), `createdBy` (Teacher). 자식 `records` (cascade=ALL, orphanRemoval=true)
- **AttendanceRecord 엔티티**: `session`, `student`, `status`, `markedAt`, `markedBy` (Teacher, NOT NULL — 회차 생성 시 createdBy 로 채움), `note` (nullable)
- **AttendanceStatus**: `PRESENT` / `LATE` / `ABSENT` / `EXCUSED`. UNMARKED 없음 — 회차 생성과 동시에 모든 ACTIVE 수강생이 ABSENT 로 시작
- **유니크 제약**: `(attendance_session_id, student_id)` — DB 레벨 UNIQUE KEY `uq_ar_session_student` + 동시 PUT 방어

---

## 주요 플로우

prefix: `/api/courses/{courseId}/attendance`

| 메서드/경로 | 권한 | 용도 |
|---|---|---|
| `GET ./sessions` | TEACHER | 회차 목록 (`PageResponse`, `sessionDate DESC`) |
| `POST ./sessions` | TEACHER | 회차 생성 → ACTIVE 수강생 전체에 ABSENT record 자동 생성 |
| `GET ./sessions/{sid}` | TEACHER | 회차 상세 (records 미포함) |
| `PATCH ./sessions/{sid}` | TEACHER | 메타 수정 (title/sessionDate/startTime/endTime/lectureId) |
| `DELETE ./sessions/{sid}` | TEACHER | 회차 삭제 (cascade로 records 정리) |
| `GET ./sessions/{sid}/records` | TEACHER | 회차의 학생별 record 배열 |
| `PUT ./sessions/{sid}/records` | TEACHER | 출석부 일괄 upsert (`items: [{ studentId, status, note? }]`) |
| `GET ./me` | STUDENT (ACTIVE) | 본인 출석 요약 (회차별 status + presentRatio) |
| `GET ./summary` | TEACHER | 학생 X 회차 매트릭스 (`sessions: List`, `students: PageResponse`) |

---

## 회차 생성 흐름

`AttendanceSessionService.createSession(...)`:
1. `courseAccessService.loadCourseAsTeacher(courseId)` → Course
2. **`dto.lectureId != null` 이면 lecture 조회 + `lecture.getCourse().getId().equals(course.getId())` 검증** — 다른 강의실의 lecture 를 출석 세션에 연결하는 것 차단. 위반 시 `INVALID_PARAMETER`
3. `currentUserResolver.getTeacher()` (loadCourseAsTeacher 가 이미 통과 → 안전)
4. `AttendanceSession` 저장
5. `enrollmentRepository.findByCourseAndStatusWithStudentUser(course, ACTIVE)` — JOIN FETCH 로 student.user 까지 한 번에
6. 각 enrollment 마다 `AttendanceRecord(status=ABSENT, markedAt=now, markedBy=teacher)` 생성 → `recordRepository.saveAll(...)`

PATCH(세션 수정) 도 `lectureId` 변경 시 동일 검증.

---

## 출석부 일괄 저장 (분산 락 + 트랜잭션)

`AttendanceRecordService.bulkUpsert(...)`:

```java
String lockKey = "attendance-bulk:" + sessionId;
distributedLockService.executeWithLock(lockKey, 3, 5, () ->
    transactionTemplate.execute(status -> {
        bulkUpsertInTx(courseId, sessionId, dto);
        return null;
    })
);
```

`bulkUpsertInTx` 내부:
1. `loadCourseAsTeacher` 재검증 (락 안)
2. `findByIdAndCourse(sessionId, course)` → 다른 강의실 sessionId 끼워넣기 차단
3. `recordRepository.findBySession(session)` 한 번에 로드 → `Map<studentId, AttendanceRecord>`
4. 요청 item 마다:
   - 기존 record 있음 → status/note/markedBy/markedAt 갱신 (dirty checking)
   - 없음 (신규 insert) → **반드시 `existsByStudentAndCourseAndStatus(student, course, ACTIVE)` 검증** + insert
5. UNIQUE 제약 (`uq_ar_session_student`) 가 동시 insert 충돌도 방어

---

## summary 매트릭스 형태

`CourseAttendanceMatrixResponseDto`:
- `sessions: List<SessionHeaderDto>` — 비페이징 회차 헤더 (sessionId, title, sessionDate)
- `students: PageResponse<StudentAttendanceMatrixRowDto>` — 학생 페이징
  - `records: Map<sessionId, AttendanceStatus>`
  - `presentRatio: PRESENT 개수 / 전체 세션 수` — LATE/EXCUSED 가중 X (1차 단순화)

학생 페이징은 in-memory slice (`PageResponse.ofSlice`). 강의실당 학생 수 보통 100명 미만.

---

## 상시 주의사항 (Gotcha)

### `lecture_id` ON DELETE SET NULL — 의도된 동작
DDL: `CONSTRAINT FK_as_lecture FOREIGN KEY (lecture_id) REFERENCES lectures(lecture_id) ON DELETE SET NULL`.

`LectureService.deleteLecture()` 가 다수 연관 데이터를 수동 삭제하지만 출석 세션은 그 흐름에 포함되지 않는다. lecture 삭제 시 attendance_session 은 살아남고 `lecture_id` 만 NULL 로 떨어진다 (= 독립 회차로 전환). 이는 출석 기록 보존을 위한 **의도된 동작**.

### 신규 insert 분기에서 ACTIVE 수강생 재검증
PUT /records 의 신규 insert 분기는 다른 강의실 학생 ID / 비ACTIVE 학생 ID 가 끼어들 수 있다. 반드시 `enrollmentRepository.existsByStudentAndCourseAndStatus(student, course, ACTIVE)` 로 재검증. 위반 시 `FORBIDDEN`.

### markedBy NOT NULL — 회차 생성 시 createdBy 로 채움
status=ABSENT 도 `markedBy` 를 비워두지 않는다. 누가 (자동) 체크했는지 추적용 — 회차 생성 교사가 채워지고, 이후 PUT /records 에서 갱신될 때 호출 교사로 변경.

### 분산 락 + TransactionTemplate 패턴 (commit 전 락 해제 금지)
단순 `@Transactional` + select-then-save 만으로는 commit 전에 락이 풀려 동시 PUT 시 race 가능. CourseJoinRequestService 의 `executeWithLock(...) → transactionTemplate.execute(...)` 패턴을 그대로 따를 것.

### `PageResponse.ofSlice` 사용
매트릭스의 학생 페이징은 데이터를 메모리에서 자르는 in-memory slice. `PageableSupport.validate` 를 거치지 않아도 되지만, 페이지 사이즈가 크면 메모리 부담. 강의실 학생 수가 수백 명을 넘기면 DB 페이징으로 전환 검토.

### `presentRatio` 정의 고정
`PRESENT / 전체 세션 수`. `LATE` 가중 또는 `EXCUSED` 제외 같은 정책 변경은 후속 PR 에서. 클라이언트 (FE) 측에 `lateCount`, `excusedCount` 까지 반환하므로 UI 에서 자체 계산 가능.

---

## 주요 파일

### Controller
- `controller/AttendanceSessionController.java` — 회차 CRUD
- `controller/AttendanceRecordController.java` — records 조회/일괄 + 학생 본인 요약 + 교사 매트릭스

### Service
- `service/AttendanceSessionService.java` — 회차 CRUD + ACTIVE 수강생 ABSENT 일괄 생성
- `service/AttendanceRecordService.java` — bulkUpsert (분산 락) + 학생/교사 요약 계산

### Repository
- `repository/AttendanceSessionRepository.java` — `findByCourse(Pageable)`, `findByCourseOrderBySessionDateDesc`, `findByIdAndCourse`
- `repository/AttendanceRecordRepository.java` — `findBySession`, `findByCourseAndStudentWithSession` (학생 본인), `findAllByCourseWithSession` (매트릭스, JOIN FETCH)

### Entity
- `entity/AttendanceSession.java` — `lecture` nullable
- `entity/AttendanceRecord.java` — UNIQUE(`attendance_session_id`, `student_id`)
- `entity/AttendanceStatus.java` — `PRESENT`/`LATE`/`ABSENT`/`EXCUSED`

### DTO
- `dto/AttendanceSessionCreateRequestDto`, `AttendanceSessionUpdateRequestDto`, `AttendanceSessionResponseDto`
- `dto/AttendanceRecordsBulkUpsertRequestDto` (with `Item` 내부 record), `AttendanceRecordResponseDto`
- `dto/StudentAttendanceSummaryResponseDto` — 학생 본인 응답
- `dto/CourseAttendanceMatrixResponseDto` — 교사 매트릭스 (`SessionHeaderDto`, `StudentAttendanceMatrixRowDto` 내부)

### 의존
- `course.service.CourseAccessService`
- `course.repository.EnrollmentRepository.{existsByStudentAndCourseAndStatus, findByCourseAndStatusWithStudentUser}`
- `course.lecture.repository.LectureRepository.findById` — lectureId 검증
- `user.repository.StudentRepository.findById` — 신규 insert 분기에서 student 조회
- `service.DistributedLockService.executeWithLock`
- `org.springframework.transaction.support.TransactionTemplate`

### 참고
- 마이그레이션: `V2__course_interaction_features.sql` (attendance_sessions / attendance_records 포함)
