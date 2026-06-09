package io.github.uou_capstone.aiplatform.domain.submission.service;

import io.github.uou_capstone.aiplatform.domain.assessment.entity.Assessment;
import io.github.uou_capstone.aiplatform.domain.assessment.entity.AssessmentType;
import io.github.uou_capstone.aiplatform.domain.assessment.repository.AssessmentRepository;
import io.github.uou_capstone.aiplatform.domain.assessment.repository.ChoiceOptionRepository;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.service.StudentReportAnalysisInvalidationService;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamQuestion;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamQuestionRepository;
import io.github.uou_capstone.aiplatform.domain.notification.service.TeacherNotificationPublisher;
import io.github.uou_capstone.aiplatform.domain.submission.dto.StudentAnswerRequestDto;
import io.github.uou_capstone.aiplatform.domain.submission.dto.SubmissionRequestDto;
import io.github.uou_capstone.aiplatform.domain.submission.entity.StudentAnswer;
import io.github.uou_capstone.aiplatform.domain.submission.entity.Submission;
import io.github.uou_capstone.aiplatform.domain.submission.repository.StudentAnswerRepository;
import io.github.uou_capstone.aiplatform.domain.submission.repository.SubmissionRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubmissionServiceTest {

    @Mock private SubmissionRepository submissionRepository;
    @Mock private StudentAnswerRepository studentAnswerRepository;
    @Mock private AssessmentRepository assessmentRepository;
    @Mock private ExamQuestionRepository examQuestionRepository;
    @Mock private ChoiceOptionRepository choiceOptionRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private CurrentUserResolver currentUserResolver;
    @Mock private TeacherNotificationPublisher teacherNotificationPublisher;
    @Mock private StudentReportAnalysisInvalidationService analysisInvalidationService;

    @InjectMocks
    private SubmissionService service;

    private Course course;
    private Student student;
    private Assessment assessment;

    @BeforeEach
    void setUp() {
        User user = User.builder().email("s@example.com").password("p").fullName("student").build();
        ReflectionTestUtils.setField(user, "id", 200L);
        student = Student.builder().user(user).grade(1).classNumber("1-1").build();
        ReflectionTestUtils.setField(student, "id", 100L);
        ReflectionTestUtils.setField(user, "student", student);

        course = Course.builder().teacher(null).title("course").description("d").invitationCode("code").build();
        ReflectionTestUtils.setField(course, "id", 50L);

        assessment = Assessment.builder()
                .course(course)
                .title("quiz")
                .type(AssessmentType.QUIZ)
                .dueDate(null)
                .build();
        ReflectionTestUtils.setField(assessment, "id", 500L);
    }

    @Test
    void createSubmissionInvalidatesStudentAnalysis() {
        ExamQuestion question = ExamQuestion.builder()
                .assessment(assessment)
                .examType(ExamType.OX_PROBLEM)
                .questionContent("Q")
                .build();
        ReflectionTestUtils.setField(question, "id", 10L);

        StudentAnswerRequestDto answer = new StudentAnswerRequestDto();
        ReflectionTestUtils.setField(answer, "questionId", 10L);
        ReflectionTestUtils.setField(answer, "descriptiveAnswer", "O");
        SubmissionRequestDto request = new SubmissionRequestDto();
        ReflectionTestUtils.setField(request, "answers", List.of(answer));

        when(currentUserResolver.getStudent()).thenReturn(student);
        when(assessmentRepository.findById(500L)).thenReturn(Optional.of(assessment));
        when(enrollmentRepository.existsByStudentAndCourse(student, course)).thenReturn(true);
        when(submissionRepository.existsByStudentAndAssessment(student, assessment)).thenReturn(false);
        when(submissionRepository.save(any(Submission.class))).thenAnswer(invocation -> {
            Submission submission = invocation.getArgument(0);
            ReflectionTestUtils.setField(submission, "id", 3000L);
            return submission;
        });
        when(examQuestionRepository.findById(10L)).thenReturn(Optional.of(question));
        when(studentAnswerRepository.save(any(StudentAnswer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.createSubmission(500L, request);

        verify(analysisInvalidationService).invalidateStudent(50L, 100L, "submission_created");
    }
}
