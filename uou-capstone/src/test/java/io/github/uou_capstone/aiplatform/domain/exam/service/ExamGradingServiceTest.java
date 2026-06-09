package io.github.uou_capstone.aiplatform.domain.exam.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.service.StudentReportAnalysisInvalidationService;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamResult;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamResultRepository;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamSessionRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.Role;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.integration.fastapi.FastApiBridgeClient;
import io.github.uou_capstone.aiplatform.service.AsyncTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExamGradingServiceTest {

    @Mock private ExamSessionRepository examSessionRepository;
    @Mock private ExamResultRepository examResultRepository;
    @Mock private AsyncTaskService asyncTaskService;
    @Mock private FastApiBridgeClient fastApiBridgeClient;
    @Mock private StudentReportAnalysisInvalidationService analysisInvalidationService;

    private ExamGradingService service;
    private ExamSession session;
    private ExamResult examResult;

    @BeforeEach
    void setUp() {
        service = new ExamGradingService(
                examSessionRepository,
                examResultRepository,
                new ObjectMapper(),
                asyncTaskService,
                fastApiBridgeClient,
                analysisInvalidationService
        );

        Student student = Student.builder().grade(1).classNumber("1-1").build();
        ReflectionTestUtils.setField(student, "id", 100L);
        User user = User.builder()
                .email("s@example.com")
                .password("p")
                .fullName("student")
                .role(Role.STUDENT)
                .build();
        ReflectionTestUtils.setField(user, "id", 300L);
        ReflectionTestUtils.setField(user, "student", student);

        Course course = Course.builder().teacher(null).title("course").description("d").invitationCode("code").build();
        ReflectionTestUtils.setField(course, "id", 50L);
        Lecture lecture = Lecture.builder().course(course).title("lecture").weekNumber(1).description("d").build();
        ReflectionTestUtils.setField(lecture, "id", 600L);

        session = ExamSession.builder()
                .lecture(lecture)
                .material(null)
                .displayName("quiz")
                .user(user)
                .examType(ExamType.OX_PROBLEM)
                .targetCount(1)
                .build();
        ReflectionTestUtils.setField(session, "id", 700L);
        session.updateExamContent(Map.of(
                "oxProblems", List.of(Map.of("answer", "O"))
        ));

        examResult = ExamResult.builder()
                .examSession(session)
                .submission(null)
                .user(user)
                .build();
        ReflectionTestUtils.setField(examResult, "id", 9000L);

        when(examSessionRepository.findById(700L)).thenReturn(Optional.of(session));
        when(examResultRepository.save(any(ExamResult.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void gradeAndSaveResult_normalizesRatioScoreWhenMaxScoreMissing() {
        when(fastApiBridgeClient.gradeResult(any())).thenReturn("""
                {
                  "grading": {
                    "total_score": 0.8,
                    "overall_feedback": "ok",
                    "results": []
                  },
                  "passed": true
                }
                """);

        service.gradeAndSaveResult(examResult, List.of(Map.of("user_response", "O")));

        assertThat(examResult.getTotalScore()).isEqualByComparingTo(new BigDecimal("80.00"));
        assertThat(examResult.getMaxScore()).isEqualByComparingTo(new BigDecimal("100"));
        verify(analysisInvalidationService).invalidateStudent(50L, 100L, "exam_result_graded");
    }

    @Test
    void gradeAndSaveResult_usesHundredPointScoreWhenMaxScoreMissing() {
        when(fastApiBridgeClient.gradeResult(any())).thenReturn("""
                {
                  "grading": {
                    "total_score": 80,
                    "overall_feedback": "ok",
                    "results": []
                  },
                  "passed": true
                }
                """);

        service.gradeAndSaveResult(examResult, List.of(Map.of("user_response", "O")));

        assertThat(examResult.getTotalScore()).isEqualByComparingTo(new BigDecimal("80"));
        assertThat(examResult.getMaxScore()).isEqualByComparingTo(new BigDecimal("100"));
    }

    @Test
    void gradeAndSaveResult_keepsExplicitMaxScore() {
        when(fastApiBridgeClient.gradeResult(any())).thenReturn("""
                {
                  "grading": {
                    "total_score": 7,
                    "max_score": 10,
                    "overall_feedback": "ok",
                    "results": []
                  },
                  "passed": true
                }
                """);

        service.gradeAndSaveResult(examResult, List.of(Map.of("user_response", "O")));

        assertThat(examResult.getTotalScore()).isEqualByComparingTo(new BigDecimal("7"));
        assertThat(examResult.getMaxScore()).isEqualByComparingTo(new BigDecimal("10"));
    }

    @Test
    void gradeExamAsyncInvalidatesWhenFeedbackProfileIsSaved() {
        session.updatePriorProfile(Map.of("profile", "exists"));
        when(fastApiBridgeClient.gradeResult(any())).thenReturn("""
                {
                  "grading": {
                    "total_score": 80,
                    "max_score": 100,
                    "overall_feedback": "ok",
                    "results": []
                  },
                  "feedback_profile": {
                    "evaluationItems": []
                  }
                }
                """);
        when(examResultRepository.findById(9000L)).thenReturn(Optional.of(examResult));

        service.gradeExamAsync("task-1", 700L, List.of(Map.of("user_response", "O")), 9000L);

        verify(examResultRepository).save(examResult);
        verify(analysisInvalidationService).invalidateStudent(50L, 100L, "exam_async_feedback_updated");
        verify(asyncTaskService).updateTaskStatus(eq("task-1"), any(), eq(100), any(), any());
    }
}
