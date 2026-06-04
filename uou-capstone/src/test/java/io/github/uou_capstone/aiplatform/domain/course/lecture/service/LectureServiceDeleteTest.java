package io.github.uou_capstone.aiplatform.domain.course.lecture.service;

import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.GeneratedContentRepository;
import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.LectureRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamProfileRepository;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamSessionRepository;
import io.github.uou_capstone.aiplatform.domain.learning.service.LearningDataCleanupService;
import io.github.uou_capstone.aiplatform.domain.material.generation.GenerationSessionRepository;
import io.github.uou_capstone.aiplatform.domain.material.repository.MaterialRepository;
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
class LectureServiceDeleteTest {

    @Mock private CourseRepository courseRepository;
    @Mock private LectureRepository lectureRepository;
    @Mock private GeneratedContentRepository generatedContentRepository;
    @Mock private CurrentUserResolver currentUserResolver;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private GenerationSessionRepository generationSessionRepository;
    @Mock private ExamSessionRepository examSessionRepository;
    @Mock private ExamProfileRepository examProfileRepository;
    @Mock private LearningDataCleanupService learningDataCleanupService;

    @InjectMocks
    private LectureService lectureService;

    private Lecture lecture;
    private Teacher teacher;

    @BeforeEach
    void setUp() {
        teacher = Teacher.builder()
                .schoolName("school")
                .department("department")
                .build();
        ReflectionTestUtils.setField(teacher, "id", 20L);

        Course course = Course.builder()
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
    }

    @Test
    void deleteLecture_cleansLearningDataBeforeDeletingLectureChildren() {
        when(lectureRepository.findById(40L)).thenReturn(Optional.of(lecture));
        when(currentUserResolver.getTeacher()).thenReturn(teacher);

        lectureService.deleteLecture(40L);

        InOrder inOrder = inOrder(
                learningDataCleanupService,
                generatedContentRepository,
                generationSessionRepository,
                examSessionRepository,
                examProfileRepository,
                materialRepository,
                lectureRepository
        );
        inOrder.verify(learningDataCleanupService).deleteByLectureId(40L);
        inOrder.verify(generatedContentRepository).clearSessionByLectureId(40L);
        inOrder.verify(generationSessionRepository).deleteByLectureId(40L);
        inOrder.verify(examSessionRepository).deleteByLectureId(40L);
        inOrder.verify(examProfileRepository).deleteByLectureId(40L);
        inOrder.verify(materialRepository).deleteByLectureId(40L);
        inOrder.verify(lectureRepository).delete(lecture);
    }
}
