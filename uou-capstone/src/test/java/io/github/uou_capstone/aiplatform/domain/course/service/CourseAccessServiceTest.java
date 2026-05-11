package io.github.uou_capstone.aiplatform.domain.course.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.entity.EnrollmentStatus;
import io.github.uou_capstone.aiplatform.domain.course.repository.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CourseAccessServiceTest {

    @Mock private CourseRepository courseRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private CurrentUserResolver currentUserResolver;

    @InjectMocks
    private CourseAccessService service;

    private User teacherUser;
    private User otherTeacherUser;
    private User studentUser;
    private Teacher teacher;
    private Teacher otherTeacher;
    private Student student;
    private Course course;

    private static final Long COURSE_ID = 50L;

    @BeforeEach
    void setUp() {
        teacherUser = User.builder().email("t@example.com").password("p").fullName("teacher").build();
        ReflectionTestUtils.setField(teacherUser, "id", 201L);
        teacher = Teacher.builder().schoolName("s").department("d").user(teacherUser).build();
        ReflectionTestUtils.setField(teacher, "id", 10L);
        ReflectionTestUtils.setField(teacherUser, "teacher", teacher);

        otherTeacherUser = User.builder().email("t2@example.com").password("p").fullName("other").build();
        ReflectionTestUtils.setField(otherTeacherUser, "id", 202L);
        otherTeacher = Teacher.builder().schoolName("s").department("d").user(otherTeacherUser).build();
        ReflectionTestUtils.setField(otherTeacher, "id", 11L);
        ReflectionTestUtils.setField(otherTeacherUser, "teacher", otherTeacher);

        studentUser = User.builder().email("stu@example.com").password("p").fullName("stu").build();
        ReflectionTestUtils.setField(studentUser, "id", 200L);
        student = Student.builder().user(studentUser).grade(1).classNumber("1-1").build();
        ReflectionTestUtils.setField(student, "id", 100L);
        ReflectionTestUtils.setField(studentUser, "student", student);

        course = Course.builder().teacher(teacher).title("c").description("d").invitationCode("code").build();
        ReflectionTestUtils.setField(course, "id", COURSE_ID);
    }

    // ---------- loadCourseAsTeacher ----------

    @Test
    @DisplayName("loadCourseAsTeacher: 본인 강의실 → 정상 반환")
    void loadCourseAsTeacher_success() {
        when(currentUserResolver.getUser()).thenReturn(teacherUser);
        when(courseRepository.findById(COURSE_ID)).thenReturn(Optional.of(course));

        Course result = service.loadCourseAsTeacher(COURSE_ID);

        assertThat(result).isSameAs(course);
    }

    @Test
    @DisplayName("loadCourseAsTeacher: 학생 사용자 → MEMBER_NOT_FOUND 가 아니라 FORBIDDEN")
    void loadCourseAsTeacher_studentUser_forbidden() {
        when(currentUserResolver.getUser()).thenReturn(studentUser);

        assertThatThrownBy(() -> service.loadCourseAsTeacher(COURSE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("loadCourseAsTeacher: 타인 강의실 → FORBIDDEN")
    void loadCourseAsTeacher_otherCourse_forbidden() {
        when(currentUserResolver.getUser()).thenReturn(otherTeacherUser);
        when(courseRepository.findById(COURSE_ID)).thenReturn(Optional.of(course));

        assertThatThrownBy(() -> service.loadCourseAsTeacher(COURSE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("loadCourseAsTeacher: 강의실 없음 → COURSE_NOT_FOUND")
    void loadCourseAsTeacher_courseMissing() {
        when(currentUserResolver.getUser()).thenReturn(teacherUser);
        when(courseRepository.findById(COURSE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadCourseAsTeacher(COURSE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COURSE_NOT_FOUND);
    }

    // ---------- loadCourseAsParticipant ----------

    @Test
    @DisplayName("loadCourseAsParticipant: 강의실 교사 → OK")
    void loadCourseAsParticipant_teacher_ok() {
        when(currentUserResolver.getUser()).thenReturn(teacherUser);
        when(courseRepository.findById(COURSE_ID)).thenReturn(Optional.of(course));

        assertThat(service.loadCourseAsParticipant(COURSE_ID)).isSameAs(course);
    }

    @Test
    @DisplayName("loadCourseAsParticipant: ACTIVE 학생 → OK")
    void loadCourseAsParticipant_activeStudent_ok() {
        when(currentUserResolver.getUser()).thenReturn(studentUser);
        when(courseRepository.findById(COURSE_ID)).thenReturn(Optional.of(course));
        when(enrollmentRepository.existsByStudentAndCourseAndStatus(
                eq(student), eq(course), eq(EnrollmentStatus.ACTIVE))).thenReturn(true);

        assertThat(service.loadCourseAsParticipant(COURSE_ID)).isSameAs(course);
    }

    @Test
    @DisplayName("loadCourseAsParticipant: 비ACTIVE 학생 → FORBIDDEN")
    void loadCourseAsParticipant_inactiveStudent_forbidden() {
        when(currentUserResolver.getUser()).thenReturn(studentUser);
        when(courseRepository.findById(COURSE_ID)).thenReturn(Optional.of(course));
        when(enrollmentRepository.existsByStudentAndCourseAndStatus(
                eq(student), eq(course), eq(EnrollmentStatus.ACTIVE))).thenReturn(false);

        assertThatThrownBy(() -> service.loadCourseAsParticipant(COURSE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("loadCourseAsParticipant: 다른 교사 → FORBIDDEN")
    void loadCourseAsParticipant_otherTeacher_forbidden() {
        when(currentUserResolver.getUser()).thenReturn(otherTeacherUser);
        when(courseRepository.findById(COURSE_ID)).thenReturn(Optional.of(course));

        assertThatThrownBy(() -> service.loadCourseAsParticipant(COURSE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
    }

    // ---------- isCourseTeacher ----------

    @Test
    @DisplayName("isCourseTeacher: 본인 강의실 교사 true / 다른 교사 false / 학생 false")
    void isCourseTeacher() {
        assertThat(service.isCourseTeacher(course, teacherUser)).isTrue();
        assertThat(service.isCourseTeacher(course, otherTeacherUser)).isFalse();
        assertThat(service.isCourseTeacher(course, studentUser)).isFalse();
    }

    // ---------- ensureAuthor / ensureAuthorOrCourseTeacher ----------

    @Test
    @DisplayName("ensureAuthor: 작성자 본인 OK / 다른 사용자 FORBIDDEN")
    void ensureAuthor() {
        // 본인
        service.ensureAuthor(studentUser, studentUser.getId());

        // 다른 사용자
        assertThatThrownBy(() -> service.ensureAuthor(studentUser, teacherUser.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("ensureAuthorOrCourseTeacher: 작성자 OK / 강의실 교사 OK / 다른 사용자 FORBIDDEN")
    void ensureAuthorOrCourseTeacher() {
        // 작성자
        service.ensureAuthorOrCourseTeacher(studentUser, course, studentUser.getId());

        // 강의실 교사 (작성자 != current 이지만 강의실 owner 라 OK)
        service.ensureAuthorOrCourseTeacher(teacherUser, course, studentUser.getId());

        // 다른 교사 — 작성자도 아니고 강의실 교사도 아님
        assertThatThrownBy(() -> service.ensureAuthorOrCourseTeacher(otherTeacherUser, course, studentUser.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
    }
}
