package io.github.uou_capstone.aiplatform.domain.course.service;

import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.GeneratedContentRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamProfileRepository;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamSessionRepository;
import io.github.uou_capstone.aiplatform.domain.exam.service.ExamGenerationService;
import io.github.uou_capstone.aiplatform.domain.learning.service.LearningDataCleanupService;
import io.github.uou_capstone.aiplatform.domain.material.generation.GenerationSessionRepository;
import io.github.uou_capstone.aiplatform.domain.material.generation.service.MaterialGenerationService;
import io.github.uou_capstone.aiplatform.domain.material.repository.MaterialRepository;
import io.github.uou_capstone.aiplatform.domain.material.service.MaterialService;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CourseServiceDeleteTest {

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
    private Teacher teacher;

    @BeforeEach
    void setUp() {
        teacher = Teacher.builder()
                .schoolName("school")
                .department("department")
                .build();
        ReflectionTestUtils.setField(teacher, "id", 20L);

        course = Course.builder()
                .teacher(teacher)
                .title("course")
                .description("description")
                .invitationCode("invite")
                .build();
        ReflectionTestUtils.setField(course, "id", 30L);
    }

    @Test
    void deleteCourse_cleansLearningDataBeforeDeletingLectureChildren() {
        when(courseRepository.findById(30L)).thenReturn(Optional.of(course));
        when(currentUserResolver.getTeacher()).thenReturn(teacher);

        courseService.deleteCourse(30L);

        InOrder inOrder = inOrder(
                learningDataCleanupService,
                generatedContentRepository,
                generationSessionRepository,
                examSessionRepository,
                examProfileRepository,
                materialRepository,
                courseRepository
        );
        inOrder.verify(learningDataCleanupService).deleteByCourseId(30L);
        inOrder.verify(generatedContentRepository).clearSessionByCourseId(30L);
        inOrder.verify(generationSessionRepository).deleteByLectureCourseId(30L);
        inOrder.verify(examSessionRepository).deleteByLectureCourseId(30L);
        inOrder.verify(examProfileRepository).deleteByLectureCourseId(30L);
        inOrder.verify(materialRepository).deleteByLectureCourseId(30L);
        inOrder.verify(courseRepository).delete(course);
    }
}
