package io.github.uou_capstone.aiplatform.domain.course.attendance.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.attendance.dto.AttendanceRecordsBulkUpsertRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.attendance.entity.AttendanceRecord;
import io.github.uou_capstone.aiplatform.domain.course.attendance.entity.AttendanceSession;
import io.github.uou_capstone.aiplatform.domain.course.attendance.entity.AttendanceStatus;
import io.github.uou_capstone.aiplatform.domain.course.attendance.repository.AttendanceRecordRepository;
import io.github.uou_capstone.aiplatform.domain.course.attendance.repository.AttendanceSessionRepository;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.entity.EnrollmentStatus;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseAccessService;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.domain.user.repository.StudentRepository;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttendanceRecordServiceTest {

    @Mock private AttendanceRecordRepository recordRepository;
    @Mock private AttendanceSessionRepository sessionRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private CourseAccessService courseAccessService;
    @Mock private CurrentUserResolver currentUserResolver;

    @InjectMocks
    private AttendanceRecordService service;

    private Teacher teacher;
    private Course course;
    private AttendanceSession session;
    private Student studentExisting;
    private Student studentNewActive;
    private Student studentNewInactive;

    private static final Long COURSE_ID = 50L;
    private static final Long SESSION_ID = 100L;

    @BeforeEach
    void setUp() {
        User teacherUser = User.builder().email("t@x").password("p").fullName("teacher").build();
        ReflectionTestUtils.setField(teacherUser, "id", 200L);
        teacher = Teacher.builder().schoolName("s").department("d").user(teacherUser).build();
        ReflectionTestUtils.setField(teacher, "id", 10L);

        course = Course.builder().teacher(teacher).title("c").description("d").invitationCode("code").build();
        ReflectionTestUtils.setField(course, "id", COURSE_ID);

        session = AttendanceSession.builder()
                .course(course).title("회차1").sessionDate(LocalDate.now()).createdBy(teacher).build();
        ReflectionTestUtils.setField(session, "id", SESSION_ID);

        studentExisting = makeStudent(300L, 100L, "alice");
        studentNewActive = makeStudent(301L, 101L, "bob");
        studentNewInactive = makeStudent(302L, 102L, "eve");
    }

    private Student makeStudent(Long userId, Long studentId, String name) {
        User u = User.builder().email(name + "@x").password("p").fullName(name).build();
        ReflectionTestUtils.setField(u, "id", userId);
        Student s = Student.builder().user(u).grade(1).classNumber("1-1").build();
        ReflectionTestUtils.setField(s, "id", studentId);
        return s;
    }

    @Test
    @DisplayName("bulkUpsertInTx: 기존 record 는 update, 없으면 ACTIVE 검증 후 insert")
    void bulkUpsertInTx_update_and_insert_with_active_check() {
        when(courseAccessService.loadCourseAsTeacher(COURSE_ID)).thenReturn(course);
        when(currentUserResolver.getTeacher()).thenReturn(teacher);
        when(sessionRepository.findByIdAndCourse(SESSION_ID, course)).thenReturn(Optional.of(session));

        AttendanceRecord existing = AttendanceRecord.builder()
                .session(session).student(studentExisting)
                .status(AttendanceStatus.ABSENT)
                .markedBy(teacher).build();
        ReflectionTestUtils.setField(existing, "id", 5000L);
        when(recordRepository.findBySession(session)).thenReturn(List.of(existing));

        // 신규 학생 ACTIVE 케이스
        when(studentRepository.findById(studentNewActive.getId())).thenReturn(Optional.of(studentNewActive));
        when(enrollmentRepository.existsByStudentAndCourseAndStatus(
                eq(studentNewActive), eq(course), eq(EnrollmentStatus.ACTIVE))).thenReturn(true);

        var dto = makeBulkDto(
                makeItem(studentExisting.getId(), AttendanceStatus.PRESENT, "정상 출석"),
                makeItem(studentNewActive.getId(), AttendanceStatus.LATE, "지각")
        );

        service.bulkUpsertInTx(COURSE_ID, SESSION_ID, dto);

        // 기존 record 는 update — status PRESENT 로
        assertThat(existing.getStatus()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(existing.getNote()).isEqualTo("정상 출석");
        // 새 record 는 save 호출
        verify(recordRepository).save(any(AttendanceRecord.class));
    }

    @Test
    @DisplayName("bulkUpsertInTx: 신규 insert 시 ACTIVE 아닌 학생 → FORBIDDEN")
    void bulkUpsertInTx_new_inactive_rejected() {
        when(courseAccessService.loadCourseAsTeacher(COURSE_ID)).thenReturn(course);
        when(currentUserResolver.getTeacher()).thenReturn(teacher);
        when(sessionRepository.findByIdAndCourse(SESSION_ID, course)).thenReturn(Optional.of(session));
        when(recordRepository.findBySession(session)).thenReturn(List.of());

        when(studentRepository.findById(studentNewInactive.getId())).thenReturn(Optional.of(studentNewInactive));
        when(enrollmentRepository.existsByStudentAndCourseAndStatus(
                eq(studentNewInactive), eq(course), eq(EnrollmentStatus.ACTIVE))).thenReturn(false);

        var dto = makeBulkDto(makeItem(studentNewInactive.getId(), AttendanceStatus.PRESENT, null));

        assertThatThrownBy(() -> service.bulkUpsertInTx(COURSE_ID, SESSION_ID, dto))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);

        verify(recordRepository, never()).save(any());
    }

    @Test
    @DisplayName("bulkUpsertInTx: 신규 insert 시 학생이 존재하지 않으면 MEMBER_NOT_FOUND")
    void bulkUpsertInTx_unknown_student() {
        when(courseAccessService.loadCourseAsTeacher(COURSE_ID)).thenReturn(course);
        when(currentUserResolver.getTeacher()).thenReturn(teacher);
        when(sessionRepository.findByIdAndCourse(SESSION_ID, course)).thenReturn(Optional.of(session));
        when(recordRepository.findBySession(session)).thenReturn(List.of());
        when(studentRepository.findById(9999L)).thenReturn(Optional.empty());

        var dto = makeBulkDto(makeItem(9999L, AttendanceStatus.PRESENT, null));

        assertThatThrownBy(() -> service.bulkUpsertInTx(COURSE_ID, SESSION_ID, dto))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.MEMBER_NOT_FOUND);
    }

    private static AttendanceRecordsBulkUpsertRequestDto.Item makeItem(Long studentId,
                                                                       AttendanceStatus status,
                                                                       String note) {
        var item = new AttendanceRecordsBulkUpsertRequestDto.Item();
        ReflectionTestUtils.setField(item, "studentId", studentId);
        ReflectionTestUtils.setField(item, "status", status);
        ReflectionTestUtils.setField(item, "note", note);
        return item;
    }

    private static AttendanceRecordsBulkUpsertRequestDto makeBulkDto(AttendanceRecordsBulkUpsertRequestDto.Item... items) {
        var dto = new AttendanceRecordsBulkUpsertRequestDto();
        ReflectionTestUtils.setField(dto, "items", List.of(items));
        return dto;
    }
}
