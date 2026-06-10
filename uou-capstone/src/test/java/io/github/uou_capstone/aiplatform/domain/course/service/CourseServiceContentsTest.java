package io.github.uou_capstone.aiplatform.domain.course.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseContentsResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.GeneratedContentRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamStatus;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamProfileRepository;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamSessionRepository;
import io.github.uou_capstone.aiplatform.domain.exam.service.ExamGenerationService;
import io.github.uou_capstone.aiplatform.domain.learning.service.LearningDataCleanupService;
import io.github.uou_capstone.aiplatform.domain.material.generation.GenerationSessionRepository;
import io.github.uou_capstone.aiplatform.domain.material.generation.service.MaterialGenerationService;
import io.github.uou_capstone.aiplatform.domain.material.repository.MaterialRepository;
import io.github.uou_capstone.aiplatform.domain.material.service.MaterialService;
import io.github.uou_capstone.aiplatform.domain.user.entity.Role;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CourseServiceContentsTest {

    @Mock private CourseRepository courseRepository;
    @Mock private CurrentUserResolver currentUserResolver;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private ExamSessionRepository examSessionRepository;
    @Mock private GenerationSessionRepository generationSessionRepository;
    @Mock private ExamProfileRepository examProfileRepository;
    @Mock private GeneratedContentRepository generatedContentRepository;
    @Mock private MaterialService materialService;
    @Mock private ExamGenerationService examGenerationService;
    @Mock private MaterialGenerationService materialGenerationService;
    @Mock private CourseAccessService courseAccessService;
    @Mock private LearningDataCleanupService learningDataCleanupService;

    @InjectMocks
    private CourseService courseService;

    private Course course;
    private Lecture lecture;
    private User teacherUser;

    @BeforeEach
    void setUp() {
        teacherUser = User.builder()
                .email("teacher@example.com")
                .password("p")
                .fullName("teacher")
                .role(Role.TEACHER)
                .build();
        ReflectionTestUtils.setField(teacherUser, "id", 10L);

        Teacher teacher = Teacher.builder()
                .schoolName("school")
                .department("department")
                .user(teacherUser)
                .build();
        ReflectionTestUtils.setField(teacher, "id", 20L);
        ReflectionTestUtils.setField(teacherUser, "teacher", teacher);

        course = Course.builder()
                .teacher(teacher)
                .title("course")
                .description("description")
                .invitationCode("invite")
                .build();
        ReflectionTestUtils.setField(course, "id", 30L);

        lecture = Lecture.builder()
                .course(course)
                .title("week 1")
                .weekNumber(1)
                .description("lecture")
                .build();
        ReflectionTestUtils.setField(lecture, "id", 40L);
        course.getLectures().add(lecture);
    }

    @Test
    void getCourseContents_includesExamSessionsForParticipantStudent() {
        ExamSession session = ExamSession.builder()
                .lecture(lecture)
                .user(teacherUser)
                .displayName("midterm")
                .examType(ExamType.OX_PROBLEM)
                .targetCount(10)
                .build();
        ReflectionTestUtils.setField(session, "id", 50L);
        ReflectionTestUtils.setField(session, "status", ExamStatus.READY);
        ReflectionTestUtils.setField(session, "createdAt", LocalDateTime.of(2026, 5, 22, 12, 0));

        when(courseAccessService.loadCourseAsParticipant(30L)).thenReturn(course);
        when(courseRepository.findByIdWithLectures(30L)).thenReturn(Optional.of(course));
        when(materialRepository.findByLecture_IdInOrderByLecture_IdAscCreatedAtDesc(List.of(40L)))
                .thenReturn(List.of());
        when(examSessionRepository.findByLecture_IdIn(List.of(40L))).thenReturn(List.of(session));

        CourseContentsResponseDto response = courseService.getCourseContents(30L);

        assertThat(response.getLectures()).hasSize(1);
        assertThat(response.getLectures().get(0).getExamSessions()).hasSize(1);
        assertThat(response.getLectures().get(0).getExamSessions().get(0).getExamSessionId())
                .isEqualTo(50L);
        assertThat(response.getLectures().get(0).getExamSessions().get(0).getStatus())
                .isEqualTo(ExamStatus.READY);
    }

    @Test
    void getCourseContents_blocksWhenParticipantAccessFails() {
        when(courseAccessService.loadCourseAsParticipant(30L))
                .thenThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        assertThatThrownBy(() -> courseService.getCourseContents(30L))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN));

        verify(courseRepository, never()).findByIdWithLectures(30L);
        verify(examSessionRepository, never()).findByLecture_IdIn(List.of(40L));
    }
}
