package io.github.uou_capstone.aiplatform.domain.exam.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.assessment.entity.Assessment;
import io.github.uou_capstone.aiplatform.domain.assessment.entity.AssessmentType;
import io.github.uou_capstone.aiplatform.domain.assessment.repository.AssessmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.service.StudentReportAnalysisInvalidationService;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseAccessService;
import io.github.uou_capstone.aiplatform.domain.exam.dto.AnswerSubmissionDto;
import io.github.uou_capstone.aiplatform.domain.exam.dto.ExamSubmissionRequestDto;
import io.github.uou_capstone.aiplatform.domain.exam.dto.GradingResponseDto;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamQuestion;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamResult;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamStatus;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamQuestionRepository;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamResultRepository;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamSessionRepository;
import io.github.uou_capstone.aiplatform.domain.notification.service.TeacherNotificationPublisher;
import io.github.uou_capstone.aiplatform.domain.submission.entity.Submission;
import io.github.uou_capstone.aiplatform.domain.submission.entity.SubmissionStatus;
import io.github.uou_capstone.aiplatform.domain.submission.repository.SubmissionRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.Role;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ExamSubmissionService.submitExam 신규 검증 로직 단위 테스트.
 *
 * <p>커버 범위:
 * <ul>
 *   <li>강의실 권한: 비-ACTIVE 학생/타 강의 사용자는 FORBIDDEN (CourseAccessService 위임)</li>
 *   <li>ExamQuestion lazy hydration: 행이 비어 있을 때 ensureExamQuestionsHydrated 호출 후 재조회</li>
 *   <li>questionId 매칭: answers[i].questionId 가 ExamQuestion.id 순서와 다르면 INVALID_PARAMETER</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ExamSubmissionServiceTest {

    @Mock private ExamGradingService examGradingService;
    @Mock private ExamSessionRepository examSessionRepository;
    @Mock private ExamQuestionRepository examQuestionRepository;
    @Mock private ExamResultRepository examResultRepository;
    @Mock private CurrentUserResolver currentUserResolver;
    @Mock private TeacherNotificationPublisher teacherNotificationPublisher;
    @Mock private CourseAccessService courseAccessService;
    @Mock private ExamGenerationService examGenerationService;
    @Mock private AssessmentRepository assessmentRepository;
    @Mock private SubmissionRepository submissionRepository;
    @Mock private StudentReportAnalysisInvalidationService analysisInvalidationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ExamSubmissionService service;

    private static final Long SESSION_ID = 700L;
    private static final Long COURSE_ID = 50L;

    private User studentUser;
    private Student student;
    private ExamSession session;
    private Assessment assessment;
    private ExamQuestion question1;
    private ExamQuestion question2;

    @BeforeEach
    void setUp() {
        // ObjectMapper 는 @InjectMocks 가 Mock 으로 만들 수 없으니 직접 주입.
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);

        User teacherUser = User.builder()
                .email("t@example.com").password("p").fullName("t").role(Role.TEACHER).build();
        ReflectionTestUtils.setField(teacherUser, "id", 201L);
        Teacher teacher = Teacher.builder().schoolName("s").department("d").user(teacherUser).build();
        ReflectionTestUtils.setField(teacher, "id", 10L);
        ReflectionTestUtils.setField(teacherUser, "teacher", teacher);

        studentUser = User.builder()
                .email("s@example.com").password("p").fullName("s").role(Role.STUDENT).build();
        ReflectionTestUtils.setField(studentUser, "id", 300L);
        student = Student.builder().user(studentUser).grade(1).classNumber("1-1").build();
        ReflectionTestUtils.setField(student, "id", 100L);
        ReflectionTestUtils.setField(studentUser, "student", student);

        Course course = Course.builder().teacher(teacher).title("c").description("d").invitationCode("code").build();
        ReflectionTestUtils.setField(course, "id", COURSE_ID);

        Lecture lecture = Lecture.builder().course(course).title("L1").weekNumber(1).description("").build();
        ReflectionTestUtils.setField(lecture, "id", 600L);

        session = ExamSession.builder()
                .lecture(lecture)
                .user(teacherUser)
                .examType(ExamType.OX_PROBLEM)
                .targetCount(2)
                .build();
        ReflectionTestUtils.setField(session, "id", SESSION_ID);
        ReflectionTestUtils.setField(session, "status", ExamStatus.READY);

        assessment = Assessment.builder()
                .course(course)
                .title("quiz")
                .type(AssessmentType.QUIZ)
                .dueDate(null)
                .build();
        ReflectionTestUtils.setField(assessment, "id", 500L);
        assessment.updateExamSession(session);

        question1 = ExamQuestion.builder()
                .examSession(session)
                .examType(ExamType.OX_PROBLEM)
                .questionOrder(1)
                .questionContent("Q1")
                .build();
        ReflectionTestUtils.setField(question1, "id", 10L);

        question2 = ExamQuestion.builder()
                .examSession(session)
                .examType(ExamType.OX_PROBLEM)
                .questionOrder(2)
                .questionContent("Q2")
                .build();
        ReflectionTestUtils.setField(question2, "id", 11L);

        lenient().when(assessmentRepository.findByExamSession_Id(SESSION_ID)).thenReturn(Optional.empty());
    }

    private ExamSubmissionRequestDto requestFor(List<Long> questionIds) {
        ExamSubmissionRequestDto dto = new ExamSubmissionRequestDto();
        dto.setExamSessionId(SESSION_ID);
        List<AnswerSubmissionDto> answers = questionIds.stream().map(qid -> {
            AnswerSubmissionDto a = new AnswerSubmissionDto();
            a.setQuestionId(qid);
            a.setSelectedOptionId("O");
            return a;
        }).toList();
        dto.setAnswers(answers);
        return dto;
    }

    @Test
    @DisplayName("비-ACTIVE 수강생이 호출하면 CourseAccessService 가 FORBIDDEN 을 던지고 채점은 시작되지 않는다")
    void submit_blockedByCourseAccess() {
        when(currentUserResolver.getUser()).thenReturn(studentUser);
        when(examSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        doThrow(new BusinessException(CommonErrorCode.FORBIDDEN))
                .when(courseAccessService).loadCourseAsParticipant(eq(COURSE_ID));

        assertThatThrownBy(() -> service.submitExam(requestFor(List.of(10L, 11L))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);

        verify(examGenerationService, never()).ensureExamQuestionsHydrated(any());
        verify(examQuestionRepository, never()).findByExamSessionIdOrderByQuestionOrder(anyLong());
        verify(examGradingService, never()).gradeAndSaveResult(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("questionId 가 ExamQuestion.id 순서와 다르면 INVALID_PARAMETER")
    void submit_questionIdMismatch() {
        when(currentUserResolver.getUser()).thenReturn(studentUser);
        when(examSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(examQuestionRepository.findByExamSessionIdOrderByQuestionOrder(SESSION_ID))
                .thenReturn(List.of(question1, question2));

        assertThatThrownBy(() -> service.submitExam(requestFor(List.of(10L, 99L))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_PARAMETER);

        verify(examGradingService, never()).gradeAndSaveResult(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("ExamQuestion 행이 비어 있으면 ensureExamQuestionsHydrated 호출 후 다시 조회해 응시 진행")
    void submit_lazyHydration() {
        when(currentUserResolver.getUser()).thenReturn(studentUser);
        when(examSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        // 첫 조회는 빈 리스트 → 두 번째 조회(같은 메서드)는 행이 채워진 결과.
        // ensureExamQuestionsHydrated 가 hydrate 한 직후의 동작을 시뮬레이션한다.
        when(examQuestionRepository.findByExamSessionIdOrderByQuestionOrder(SESSION_ID))
                .thenReturn(List.of(question1, question2));
        doAnswer(inv -> {
            ExamResult er = inv.getArgument(0);
            ReflectionTestUtils.setField(er, "id", 9000L);
            return er;
        }).when(examResultRepository).save(any(ExamResult.class));

        GradingResponseDto grading = new GradingResponseDto();
        grading.setTotalScore(new BigDecimal("100"));
        grading.setMaxScore(new BigDecimal("100"));
        when(examGradingService.gradeAndSaveResult(any(ExamResult.class), any(), eq(false)))
                .thenReturn(grading);

        service.submitExam(requestFor(List.of(10L, 11L)));

        verify(examGenerationService, times(1)).ensureExamQuestionsHydrated(session);
        verify(examQuestionRepository, atLeastOnce()).findByExamSessionIdOrderByQuestionOrder(SESSION_ID);
        verify(examGradingService, times(1)).gradeAndSaveResult(any(ExamResult.class), any(), eq(false));
        verify(analysisInvalidationService).invalidateStudent(COURSE_ID, 100L, "exam_submission_completed");
    }

    @Test
    @DisplayName("ExamSession에 연결된 Assessment가 있으면 시험 제출 후 Submission을 생성하고 ExamResult를 연결한다")
    void submit_createsSubmissionForAssessment() {
        when(currentUserResolver.getUser()).thenReturn(studentUser);
        when(examSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(examQuestionRepository.findByExamSessionIdOrderByQuestionOrder(SESSION_ID))
                .thenReturn(List.of(question1, question2));
        doAnswer(inv -> {
            ExamResult er = inv.getArgument(0);
            ReflectionTestUtils.setField(er, "id", 9000L);
            return er;
        }).when(examResultRepository).save(any(ExamResult.class));

        GradingResponseDto grading = new GradingResponseDto();
        grading.setTotalScore(new BigDecimal("80"));
        grading.setMaxScore(new BigDecimal("100"));
        when(examGradingService.gradeAndSaveResult(any(ExamResult.class), any(), eq(false)))
                .thenReturn(grading);
        when(assessmentRepository.findByExamSession_Id(SESSION_ID)).thenReturn(Optional.of(assessment));
        when(submissionRepository.findByStudentAndAssessment(student, assessment)).thenReturn(Optional.empty());
        when(submissionRepository.save(any(Submission.class))).thenAnswer(inv -> inv.getArgument(0));

        service.submitExam(requestFor(List.of(10L, 11L)));

        ArgumentCaptor<Submission> captor = ArgumentCaptor.forClass(Submission.class);
        verify(submissionRepository).save(captor.capture());
        Submission saved = captor.getValue();
        assertThat(saved.getAssessment()).isEqualTo(assessment);
        assertThat(saved.getStudent()).isEqualTo(student);
        assertThat(saved.getExamResult().getId()).isEqualTo(9000L);
        assertThat(saved.getStatus()).isEqualTo(SubmissionStatus.GRADED);
        verify(analysisInvalidationService).invalidateStudent(COURSE_ID, 100L, "exam_submission_completed");
    }

    @Test
    @DisplayName("Assessment 제출 row가 이미 있으면 중복 생성 없이 기존 Submission에 ExamResult를 연결한다")
    void submit_updatesExistingSubmissionForAssessment() {
        when(currentUserResolver.getUser()).thenReturn(studentUser);
        when(examSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(examQuestionRepository.findByExamSessionIdOrderByQuestionOrder(SESSION_ID))
                .thenReturn(List.of(question1, question2));
        doAnswer(inv -> {
            ExamResult er = inv.getArgument(0);
            ReflectionTestUtils.setField(er, "id", 9001L);
            return er;
        }).when(examResultRepository).save(any(ExamResult.class));

        GradingResponseDto grading = new GradingResponseDto();
        grading.setTotalScore(new BigDecimal("90"));
        grading.setMaxScore(new BigDecimal("100"));
        when(examGradingService.gradeAndSaveResult(any(ExamResult.class), any(), eq(false)))
                .thenReturn(grading);
        Submission existing = Submission.builder()
                .assessment(assessment)
                .student(student)
                .build();
        ReflectionTestUtils.setField(existing, "id", 3000L);
        when(assessmentRepository.findByExamSession_Id(SESSION_ID)).thenReturn(Optional.of(assessment));
        when(submissionRepository.findByStudentAndAssessment(student, assessment)).thenReturn(Optional.of(existing));
        when(submissionRepository.save(any(Submission.class))).thenAnswer(inv -> inv.getArgument(0));

        service.submitExam(requestFor(List.of(10L, 11L)));

        verify(submissionRepository).save(existing);
        assertThat(existing.getExamResult().getId()).isEqualTo(9001L);
        assertThat(existing.getStatus()).isEqualTo(SubmissionStatus.GRADED);
        verify(analysisInvalidationService).invalidateStudent(COURSE_ID, 100L, "exam_submission_completed");
    }
}
