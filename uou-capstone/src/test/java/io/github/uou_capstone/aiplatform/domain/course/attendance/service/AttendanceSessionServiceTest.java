package io.github.uou_capstone.aiplatform.domain.course.attendance.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.attendance.dto.AttendanceSessionCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.attendance.entity.AttendanceRecord;
import io.github.uou_capstone.aiplatform.domain.course.attendance.entity.AttendanceSession;
import io.github.uou_capstone.aiplatform.domain.course.attendance.entity.AttendanceStatus;
import io.github.uou_capstone.aiplatform.domain.course.attendance.repository.AttendanceRecordRepository;
import io.github.uou_capstone.aiplatform.domain.course.attendance.repository.AttendanceSessionRepository;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.entity.Enrollment;
import io.github.uou_capstone.aiplatform.domain.course.entity.EnrollmentStatus;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.LectureRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseAccessService;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttendanceSessionServiceTest {

    @Mock private AttendanceSessionRepository sessionRepository;
    @Mock private AttendanceRecordRepository recordRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private LectureRepository lectureRepository;
    @Mock private CourseAccessService courseAccessService;
    @Mock private CurrentUserResolver currentUserResolver;

    @InjectMocks
    private AttendanceSessionService service;

    private Teacher teacher;
    private Course course;
    private Course otherCourse;
    private Lecture courseLecture;
    private Lecture otherCourseLecture;
    private Student student1;
    private Student student2;

    private static final Long COURSE_ID = 50L;

    @BeforeEach
    void setUp() {
        User teacherUser = User.builder().email("t@x").password("p").fullName("teacher").build();
        ReflectionTestUtils.setField(teacherUser, "id", 200L);
        teacher = Teacher.builder().schoolName("s").department("d").user(teacherUser).build();
        ReflectionTestUtils.setField(teacher, "id", 10L);

        Teacher otherTeacher = Teacher.builder().schoolName("s").department("d").user(teacherUser).build();
        ReflectionTestUtils.setField(otherTeacher, "id", 11L);

        course = Course.builder().teacher(teacher).title("c").description("d").invitationCode("code").build();
        ReflectionTestUtils.setField(course, "id", COURSE_ID);

        otherCourse = Course.builder().teacher(otherTeacher).title("o").description("d").invitationCode("code2").build();
        ReflectionTestUtils.setField(otherCourse, "id", 51L);

        courseLecture = Lecture.builder().course(course).title("L1").weekNumber(1).description("d").build();
        ReflectionTestUtils.setField(courseLecture, "id", 1000L);

        otherCourseLecture = Lecture.builder().course(otherCourse).title("L1").weekNumber(1).description("d").build();
        ReflectionTestUtils.setField(otherCourseLecture, "id", 2000L);

        User u1 = User.builder().email("s1@x").password("p").fullName("alice").build();
        ReflectionTestUtils.setField(u1, "id", 300L);
        student1 = Student.builder().user(u1).grade(1).classNumber("1-1").build();
        ReflectionTestUtils.setField(student1, "id", 100L);

        User u2 = User.builder().email("s2@x").password("p").fullName("bob").build();
        ReflectionTestUtils.setField(u2, "id", 301L);
        student2 = Student.builder().user(u2).grade(1).classNumber("1-2").build();
        ReflectionTestUtils.setField(student2, "id", 101L);
    }

    @Test
    @DisplayName("createSession: ACTIVE 수강생 N명 → AttendanceRecord N개 자동 생성 (status=ABSENT)")
    void createSession_auto_creates_absent_records() {
        when(courseAccessService.loadCourseAsTeacher(COURSE_ID)).thenReturn(course);
        when(currentUserResolver.getTeacher()).thenReturn(teacher);

        AttendanceSession persisted = AttendanceSession.builder()
                .course(course).title("회차1").sessionDate(LocalDate.of(2026, 5, 9)).createdBy(teacher).build();
        ReflectionTestUtils.setField(persisted, "id", 999L);
        when(sessionRepository.save(any(AttendanceSession.class))).thenReturn(persisted);

        Enrollment e1 = Enrollment.builder().student(student1).course(course).build();
        Enrollment e2 = Enrollment.builder().student(student2).course(course).build();
        when(enrollmentRepository.findByCourseAndStatusWithStudentUser(course, EnrollmentStatus.ACTIVE))
                .thenReturn(List.of(e1, e2));

        var dto = new AttendanceSessionCreateRequestDto();
        ReflectionTestUtils.setField(dto, "title", "회차1");
        ReflectionTestUtils.setField(dto, "sessionDate", LocalDate.of(2026, 5, 9));

        service.createSession(COURSE_ID, dto);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AttendanceRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(recordRepository).saveAll(captor.capture());
        List<AttendanceRecord> saved = captor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved).allMatch(r -> r.getStatus() == AttendanceStatus.ABSENT);
        assertThat(saved).allMatch(r -> r.getMarkedBy().equals(teacher));
    }

    @Test
    @DisplayName("createSession: lectureId 가 다른 강의실 lecture → INVALID_PARAMETER")
    void createSession_other_course_lecture_rejected() {
        when(courseAccessService.loadCourseAsTeacher(COURSE_ID)).thenReturn(course);
        when(lectureRepository.findById(otherCourseLecture.getId()))
                .thenReturn(Optional.of(otherCourseLecture));

        var dto = new AttendanceSessionCreateRequestDto();
        ReflectionTestUtils.setField(dto, "title", "회차1");
        ReflectionTestUtils.setField(dto, "sessionDate", LocalDate.now());
        ReflectionTestUtils.setField(dto, "lectureId", otherCourseLecture.getId());

        assertThatThrownBy(() -> service.createSession(COURSE_ID, dto))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_PARAMETER);

        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("createSession: 본인 강의실 lecture → 정상 연결")
    void createSession_own_course_lecture_ok() {
        when(courseAccessService.loadCourseAsTeacher(COURSE_ID)).thenReturn(course);
        when(currentUserResolver.getTeacher()).thenReturn(teacher);
        when(lectureRepository.findById(courseLecture.getId())).thenReturn(Optional.of(courseLecture));

        AttendanceSession persisted = AttendanceSession.builder()
                .course(course).lecture(courseLecture).title("t").sessionDate(LocalDate.now())
                .createdBy(teacher).build();
        ReflectionTestUtils.setField(persisted, "id", 1L);
        when(sessionRepository.save(any(AttendanceSession.class))).thenReturn(persisted);
        when(enrollmentRepository.findByCourseAndStatusWithStudentUser(course, EnrollmentStatus.ACTIVE))
                .thenReturn(List.of());

        var dto = new AttendanceSessionCreateRequestDto();
        ReflectionTestUtils.setField(dto, "title", "t");
        ReflectionTestUtils.setField(dto, "sessionDate", LocalDate.now());
        ReflectionTestUtils.setField(dto, "lectureId", courseLecture.getId());

        service.createSession(COURSE_ID, dto);

        verify(sessionRepository).save(any(AttendanceSession.class));
    }
}
