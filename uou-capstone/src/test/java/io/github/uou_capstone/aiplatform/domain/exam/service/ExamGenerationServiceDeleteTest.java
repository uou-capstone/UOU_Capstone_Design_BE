package io.github.uou_capstone.aiplatform.domain.exam.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.domain.assessment.entity.CreatedBy;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.LectureRepository;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseAccessService;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamQuestion;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamQuestionRepository;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamSessionRepository;
import io.github.uou_capstone.aiplatform.domain.material.repository.MaterialRepository;
import io.github.uou_capstone.aiplatform.domain.notification.service.TeacherNotificationPublisher;
import io.github.uou_capstone.aiplatform.domain.user.entity.Role;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.domain.user.repository.UserRepository;
import io.github.uou_capstone.aiplatform.integration.fastapi.FastApiBridgeClient;
import io.github.uou_capstone.aiplatform.service.AsyncTaskService;
import io.github.uou_capstone.aiplatform.service.CacheService;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import io.github.uou_capstone.aiplatform.service.SessionRecoveryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExamGenerationServiceDeleteTest {

    @Mock private ExamSessionRepository examSessionRepository;
    @Mock private ExamQuestionRepository examQuestionRepository;
    @Mock private LectureRepository lectureRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private UserRepository userRepository;
    @Mock private CurrentUserResolver currentUserResolver;
    @Mock private ObjectMapper objectMapper;
    @Mock private CacheService cacheService;
    @Mock private AsyncTaskService asyncTaskService;
    @Mock private SessionRecoveryService sessionRecoveryService;
    @Mock private FastApiBridgeClient fastApiBridgeClient;
    @Mock private TeacherNotificationPublisher teacherNotificationPublisher;
    @Mock private CourseAccessService courseAccessService;

    @InjectMocks
    private ExamGenerationService service;

    @Test
    @DisplayName("deletes ExamQuestion rows before deleting ExamSession")
    void deleteExamSession_deletesQuestionsBeforeSession() {
        User teacherUser = User.builder()
                .email("teacher@example.com")
                .password("p")
                .fullName("teacher")
                .role(Role.TEACHER)
                .build();
        ReflectionTestUtils.setField(teacherUser, "id", 10L);

        Teacher teacher = Teacher.builder()
                .schoolName("uou")
                .department("cs")
                .user(teacherUser)
                .build();
        ReflectionTestUtils.setField(teacher, "id", 20L);
        ReflectionTestUtils.setField(teacherUser, "teacher", teacher);

        Course course = Course.builder()
                .teacher(teacher)
                .title("course")
                .description("desc")
                .invitationCode("code")
                .build();
        ReflectionTestUtils.setField(course, "id", 30L);

        Lecture lecture = Lecture.builder()
                .course(course)
                .title("lecture")
                .weekNumber(1)
                .description("desc")
                .build();
        ReflectionTestUtils.setField(lecture, "id", 40L);

        ExamSession session = ExamSession.builder()
                .lecture(lecture)
                .user(teacherUser)
                .examType(ExamType.OX_PROBLEM)
                .targetCount(1)
                .build();
        ReflectionTestUtils.setField(session, "id", 50L);

        ExamQuestion question = ExamQuestion.builder()
                .examSession(session)
                .examType(ExamType.OX_PROBLEM)
                .questionOrder(1)
                .questionContent("Q")
                .createdBy(CreatedBy.AI)
                .build();

        when(examSessionRepository.findById(50L)).thenReturn(Optional.of(session));
        when(currentUserResolver.getUser()).thenReturn(teacherUser);
        when(examQuestionRepository.findByExamSession(session)).thenReturn(List.of(question));

        service.deleteExamSession(50L);

        verify(cacheService).deleteExamSessionCache(50L);
        InOrder order = inOrder(examQuestionRepository, examSessionRepository);
        order.verify(examQuestionRepository).deleteAll(List.of(question));
        order.verify(examQuestionRepository).flush();
        order.verify(examSessionRepository).delete(session);
        order.verify(examSessionRepository).flush();
    }
}
