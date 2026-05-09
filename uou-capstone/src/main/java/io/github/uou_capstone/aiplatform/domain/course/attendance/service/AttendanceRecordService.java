package io.github.uou_capstone.aiplatform.domain.course.attendance.service;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.attendance.dto.AttendanceRecordResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.attendance.dto.AttendanceRecordsBulkUpsertRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.attendance.dto.CourseAttendanceMatrixResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.attendance.dto.CourseAttendanceMatrixResponseDto.SessionHeaderDto;
import io.github.uou_capstone.aiplatform.domain.course.attendance.dto.CourseAttendanceMatrixResponseDto.StudentAttendanceMatrixRowDto;
import io.github.uou_capstone.aiplatform.domain.course.attendance.dto.StudentAttendanceSummaryResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.attendance.entity.AttendanceRecord;
import io.github.uou_capstone.aiplatform.domain.course.attendance.entity.AttendanceSession;
import io.github.uou_capstone.aiplatform.domain.course.attendance.entity.AttendanceStatus;
import io.github.uou_capstone.aiplatform.domain.course.attendance.repository.AttendanceRecordRepository;
import io.github.uou_capstone.aiplatform.domain.course.attendance.repository.AttendanceSessionRepository;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.entity.Enrollment;
import io.github.uou_capstone.aiplatform.domain.course.entity.EnrollmentStatus;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseAccessService;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.repository.StudentRepository;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import io.github.uou_capstone.aiplatform.service.DistributedLockService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AttendanceRecordService {

    private final AttendanceRecordRepository recordRepository;
    private final AttendanceSessionRepository sessionRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final StudentRepository studentRepository;
    private final CourseAccessService courseAccessService;
    private final CurrentUserResolver currentUserResolver;
    private final DistributedLockService distributedLockService;
    private final TransactionTemplate transactionTemplate;

    @Transactional(readOnly = true)
    public List<AttendanceRecordResponseDto> getRecords(Long courseId, Long sessionId) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        AttendanceSession session = sessionRepository.findByIdAndCourse(sessionId, course)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return recordRepository.findBySession(session).stream()
                .map(AttendanceRecordResponseDto::new)
                .toList();
    }

    /**
     * 출석부 일괄 upsert. (분산 락 → 트랜잭션) 패턴 — 같은 세션 동시 PUT 직렬화.
     */
    public void bulkUpsert(Long courseId, Long sessionId, AttendanceRecordsBulkUpsertRequestDto dto) {
        String lockKey = "attendance-bulk:" + sessionId;
        distributedLockService.executeWithLock(lockKey, 3, 5, () ->
                transactionTemplate.execute(status -> {
                    bulkUpsertInTx(courseId, sessionId, dto);
                    return null;
                })
        );
    }

    void bulkUpsertInTx(Long courseId, Long sessionId, AttendanceRecordsBulkUpsertRequestDto dto) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        Teacher teacher = currentUserResolver.getTeacher();
        AttendanceSession session = sessionRepository.findByIdAndCourse(sessionId, course)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        // 기존 records 한 번에 로드
        List<AttendanceRecord> existing = recordRepository.findBySession(session);
        Map<Long, AttendanceRecord> byStudent = new HashMap<>();
        for (AttendanceRecord r : existing) {
            byStudent.put(r.getStudent().getId(), r);
        }

        LocalDateTime now = LocalDateTime.now();
        for (AttendanceRecordsBulkUpsertRequestDto.Item item : dto.getItems()) {
            AttendanceRecord existingRec = byStudent.get(item.getStudentId());
            if (existingRec != null) {
                existingRec.update(item.getStatus(), item.getNote(), teacher, now);
            } else {
                // 신규 insert 분기 — ACTIVE 수강생인지 재검증
                Student student = studentRepository.findById(item.getStudentId())
                        .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
                boolean isActive = enrollmentRepository
                        .existsByStudentAndCourseAndStatus(student, course, EnrollmentStatus.ACTIVE);
                if (!isActive) {
                    throw new BusinessException(CommonErrorCode.FORBIDDEN,
                            "ACTIVE 수강생이 아닌 학생은 출석부에 추가할 수 없습니다.");
                }
                recordRepository.save(AttendanceRecord.builder()
                        .session(session)
                        .student(student)
                        .status(item.getStatus())
                        .markedAt(now)
                        .markedBy(teacher)
                        .note(item.getNote())
                        .build());
            }
        }
    }

    /**
     * 학생 본인 출석 요약. presentRatio = PRESENT / 전체 세션 수.
     */
    @Transactional(readOnly = true)
    public StudentAttendanceSummaryResponseDto getMySummary(Long courseId) {
        Course course = courseAccessService.loadCourseAsParticipant(courseId);
        Student student = currentUserResolver.getStudent();

        // 강의실 전체 세션
        List<AttendanceSession> allSessions = sessionRepository.findByCourseOrderBySessionDateDesc(course);
        // 본인 records
        List<AttendanceRecord> myRecords = recordRepository.findByCourseAndStudentWithSession(course, student);
        Map<Long, AttendanceStatus> statusBySession = new HashMap<>();
        for (AttendanceRecord r : myRecords) {
            statusBySession.put(r.getSession().getId(), r.getStatus());
        }

        int present = 0, late = 0, absent = 0, excused = 0;
        List<StudentAttendanceSummaryResponseDto.SessionStatusItem> sessionItems = new ArrayList<>();
        for (AttendanceSession s : allSessions) {
            AttendanceStatus st = statusBySession.get(s.getId());
            if (st == AttendanceStatus.PRESENT) present++;
            else if (st == AttendanceStatus.LATE) late++;
            else if (st == AttendanceStatus.ABSENT) absent++;
            else if (st == AttendanceStatus.EXCUSED) excused++;
            sessionItems.add(new StudentAttendanceSummaryResponseDto.SessionStatusItem(
                    s.getId(), s.getTitle(), s.getSessionDate(), st));
        }

        return new StudentAttendanceSummaryResponseDto(
                course.getId(), allSessions.size(), present, late, absent, excused, sessionItems);
    }

    /**
     * 교사용 매트릭스. 회차 헤더는 비페이징, 학생만 페이징.
     */
    @Transactional(readOnly = true)
    public CourseAttendanceMatrixResponseDto getMatrix(Long courseId, Pageable rawStudentPageable) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);

        // 회차 헤더
        List<AttendanceSession> sessions = sessionRepository.findByCourseOrderBySessionDateDesc(course);
        List<SessionHeaderDto> sessionHeaders = sessions.stream()
                .map(s -> new SessionHeaderDto(s.getId(), s.getTitle(), s.getSessionDate()))
                .toList();

        // 학생 목록 (ACTIVE 수강생) — 페이징은 in-memory slice (학생 수 보통 100명 이하라 단순화)
        List<Enrollment> active = enrollmentRepository
                .findByCourseAndStatusWithStudentUser(course, EnrollmentStatus.ACTIVE);

        // 모든 record 한 번에 로드 후 (studentId, sessionId) 맵 구성
        List<AttendanceRecord> allRecords = recordRepository.findAllByCourseWithSession(course);
        Map<Long, Map<Long, AttendanceStatus>> bySession = new HashMap<>();
        for (AttendanceRecord r : allRecords) {
            bySession.computeIfAbsent(r.getStudent().getId(), k -> new HashMap<>())
                    .put(r.getSession().getId(), r.getStatus());
        }

        int totalSessions = sessions.size();
        List<StudentAttendanceMatrixRowDto> rows = new ArrayList<>();
        for (Enrollment e : active) {
            Student s = e.getStudent();
            Map<Long, AttendanceStatus> rec = bySession.getOrDefault(s.getId(), Map.of());
            int presentCount = 0;
            for (AttendanceStatus st : rec.values()) {
                if (st == AttendanceStatus.PRESENT) presentCount++;
            }
            double ratio = totalSessions == 0 ? 0.0 : (double) presentCount / totalSessions;
            rows.add(new StudentAttendanceMatrixRowDto(s.getId(), s.getUser().getFullName(), rec, ratio));
        }

        // 학생 페이징 (in-memory slice)
        Pageable pageable = rawStudentPageable != null
                ? rawStudentPageable
                : PageRequest.of(0, 50);
        return new CourseAttendanceMatrixResponseDto(
                sessionHeaders,
                PageResponse.ofSlice(rows, pageable)
        );
    }
}
