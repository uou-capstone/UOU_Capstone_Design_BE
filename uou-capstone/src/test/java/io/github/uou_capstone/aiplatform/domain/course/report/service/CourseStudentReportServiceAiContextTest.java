package io.github.uou_capstone.aiplatform.domain.course.report.service;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.assessment.entity.Assessment;
import io.github.uou_capstone.aiplatform.domain.assessment.entity.AssessmentType;
import io.github.uou_capstone.aiplatform.domain.assessment.repository.AssessmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.entity.Enrollment;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.AiCompetencyDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.AiCompetencyLevel;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.AiEvidenceItemDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.AiLearningEvidenceDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.AiScoreTrend;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.StudentAiReportContextResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.ReportCriterionResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.service.CourseReportCriteriaQueryService;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.StudentReportDetailResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.StudentReportListItem;
import io.github.uou_capstone.aiplatform.domain.course.repository.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamResult;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamResultRepository;
import io.github.uou_capstone.aiplatform.domain.learning.entity.LearningChatSession;
import io.github.uou_capstone.aiplatform.domain.learning.entity.LearningSessionEvidence;
import io.github.uou_capstone.aiplatform.domain.learning.repository.LearningSessionEvidenceRepository;
import io.github.uou_capstone.aiplatform.domain.material.entity.Material;
import io.github.uou_capstone.aiplatform.domain.submission.entity.Submission;
import io.github.uou_capstone.aiplatform.domain.submission.repository.SubmissionRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CourseStudentReportServiceAiContextTest {

    private static final long COURSE_ID = 50L;
    private static final long STUDENT_ID = 100L;
    private static final long STUDENT_USER_ID = 200L;
    private static final long OWNER_TEACHER_ID = 10L;
    private static final long OTHER_TEACHER_ID = 11L;

    @Mock private CourseRepository courseRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private ExamResultRepository examResultRepository;
    @Mock private SubmissionRepository submissionRepository;
    @Mock private AssessmentRepository assessmentRepository;
    @Mock private LearningSessionEvidenceRepository learningSessionEvidenceRepository;
    @Mock private CourseReportCriteriaQueryService criteriaQueryService;
    @Mock private CurrentUserResolver currentUserResolver;

    @InjectMocks
    private CourseStudentReportService service;

    private Course course;
    private Teacher owner;
    private Student student;
    private User studentUser;
    private Enrollment enrollment;

    @BeforeEach
    void setUp() {
        owner = Teacher.builder().schoolName("s").department("d").build();
        ReflectionTestUtils.setField(owner, "id", OWNER_TEACHER_ID);

        studentUser = User.builder().email("stu@example.com").password("p").fullName("홍길동").build();
        ReflectionTestUtils.setField(studentUser, "id", STUDENT_USER_ID);

        student = Student.builder().user(studentUser).grade(1).classNumber("1-1").build();
        ReflectionTestUtils.setField(student, "id", STUDENT_ID);

        course = Course.builder()
                .teacher(owner)
                .title("자료구조")
                .description("desc")
                .invitationCode("code")
                .build();
        ReflectionTestUtils.setField(course, "id", COURSE_ID);

        enrollment = Enrollment.builder().student(student).course(course).build();
        ReflectionTestUtils.setField(enrollment, "id", 1L);

        lenient().when(learningSessionEvidenceRepository.findTop50ByCourseIdAndStudentIdOrderByOccurredAtDescIdDesc(anyLong(), anyLong()))
                .thenReturn(List.of());
        lenient().when(criteriaQueryService.listAll(course)).thenReturn(List.of(
                ReportCriterionResponse.builder()
                        .id("builtin:CONCEPT_UNDERSTANDING")
                        .key("CONCEPT_UNDERSTANDING")
                        .label("개념 이해도")
                        .description("핵심 개념 이해")
                        .builtIn(true)
                        .editable(false)
                        .deletable(false)
                        .fallbackPolicy("INSUFFICIENT_EVIDENCE")
                        .build()));
    }

    private void primeOwnerAndEnrollment() {
        when(currentUserResolver.getTeacher()).thenReturn(owner);
        when(courseRepository.findById(COURSE_ID)).thenReturn(Optional.of(course));
        when(enrollmentRepository.findByCourseIdAndStudentIdWithUser(COURSE_ID, STUDENT_ID))
                .thenReturn(Optional.of(enrollment));
    }

    private void primeOwner() {
        when(currentUserResolver.getTeacher()).thenReturn(owner);
        when(courseRepository.findById(COURSE_ID)).thenReturn(Optional.of(course));
    }

    @Test
    void studentReportList_rejectsClientPageSizeOver100() {
        primeOwner();

        assertThatThrownBy(() -> service.getStudentReportList(COURSE_ID, null, null, PageRequest.of(0, 101)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_PARAMETER);
    }

    @Test
    void classroomAnalysisList_allowsInternalPageSizeOver100() {
        primeOwner();
        when(enrollmentRepository.findByCourseIdWithStudentUser(COURSE_ID)).thenReturn(List.of());

        PageResponse<StudentReportListItem> res =
                service.getStudentReportListForClassroomAnalysis(COURSE_ID, PageRequest.of(0, 1000));

        assertThat(res.getSize()).isEqualTo(1000);
        assertThat(res.getContent()).isEmpty();
    }

    private ExamResult examResultWith(Long id, double scorePercent, Map<String, Object> feedbackJson, String overall) {
        ExamSession session = ExamSession.builder()
                .lecture(null).material(null).displayName(null).user(studentUser)
                .examType(ExamType.FIVE_CHOICE).targetCount(10).build();
        ExamResult er = ExamResult.builder().examSession(session).submission(null).user(studentUser).build();
        ReflectionTestUtils.setField(er, "id", id);
        er.updateScores(BigDecimal.valueOf(scorePercent), BigDecimal.valueOf(100));
        if (feedbackJson != null) er.updateUserFeedback(feedbackJson);
        if (overall != null) er.updateOverallFeedback(overall);
        ReflectionTestUtils.setField(er, "completedAt", LocalDateTime.now().minusDays(id));
        return er;
    }

    private Submission submissionFor(Long id, Assessment assessment, ExamResult result) {
        Submission s = Submission.builder().assessment(assessment).student(student).build();
        ReflectionTestUtils.setField(s, "id", id);
        ReflectionTestUtils.setField(s, "createdAt", LocalDateTime.now().minusDays(id));
        if (result != null) s.updateExamResult(result);
        return s;
    }

    private Assessment assessment(Long id, String title) {
        Assessment a = Assessment.builder().course(course).title(title).type(AssessmentType.QUIZ).dueDate(null).build();
        ReflectionTestUtils.setField(a, "id", id);
        // createdAt 을 id 가 작을수록 더 옛날로 → ASC 정렬 시 id=1 이 index 0.
        ReflectionTestUtils.setField(a, "createdAt", LocalDateTime.of(2026, 1, (int) (long) id, 0, 0));
        return a;
    }

    private LearningSessionEvidence sessionEvidence(String evidenceId,
                                                    LearningChatSession session,
                                                    Lecture lecture,
                                                    Material material,
                                                    String eventType,
                                                    String quizType,
                                                    Double scoreRatio,
                                                    Boolean passed,
                                                    List<String> weakConcepts) {
        LearningSessionEvidence evidence = LearningSessionEvidence.builder()
                .evidenceId(evidenceId)
                .course(course)
                .lecture(lecture)
                .material(material)
                .student(student)
                .session(session)
                .pageNumber(5)
                .eventType(eventType)
                .quizType(quizType)
                .scoreRatio(scoreRatio)
                .passed(passed)
                .weakConcepts(weakConcepts)
                .wrongItems(List.of())
                .evidence(Map.of("type", "learning_evidence"))
                .occurredAt(LocalDateTime.of(2026, 1, Integer.parseInt(evidenceId.substring(evidenceId.length() - 1)), 0, 0))
                .build();
        return evidence;
    }

    @Test
    void aiContext_returnsFullDto_forOwnerAndEnrolledStudent() {
        primeOwnerAndEnrollment();

        Assessment a1 = assessment(1L, "퀴즈1");
        Assessment a2 = assessment(2L, "퀴즈2");
        when(assessmentRepository.findByCourse_Id(COURSE_ID)).thenReturn(List.of(a1, a2));
        when(assessmentRepository.countByCourse_Id(COURSE_ID)).thenReturn(2L);

        Map<String, Object> feedback = Map.of(
                "evaluationItems", List.of(
                        Map.of(
                                "score", 50.0,
                                "correct", false,
                                "feedback", "기본 개념 부족",
                                "evaluationDetails", Map.of(
                                        "competencyKey", "logic",
                                        "competencyLabel", "논리력",
                                        "weakConcepts", List.of("재귀", "반복문"))),
                        Map.of(
                                "score", 95.0,
                                "feedback", "잘함",
                                "evaluationDetails", Map.of(
                                        "competencyKey", "syntax",
                                        "competencyLabel", "문법"))));

        ExamResult er1 = examResultWith(1L, 60.0, feedback, "전반적으로 보강 필요");
        Submission s1 = submissionFor(1L, a1, er1);
        Submission s2 = submissionFor(2L, a2, null); // 채점 전

        when(examResultRepository.findByCourseIdAndUserIdWithSession(COURSE_ID, STUDENT_USER_ID))
                .thenReturn(List.of(er1));
        when(submissionRepository.findByCourseIdAndStudentIdWithAssessment(COURSE_ID, STUDENT_ID))
                .thenReturn(List.of(s1, s2));

        StudentAiReportContextResponse res = service.getStudentAiReportContext(COURSE_ID, STUDENT_ID);

        assertThat(res.getCourse().getCourseId()).isEqualTo(COURSE_ID);
        assertThat(res.getCourse().getCourseName()).isEqualTo("자료구조");
        assertThat(res.getCourse().getTeacherId()).isEqualTo(OWNER_TEACHER_ID);

        assertThat(res.getStudent().getStudentName()).isEqualTo("홍길동");
        assertThat(res.getStudent().getEnrollmentStatus()).isEqualTo("ACTIVE");

        assertThat(res.getActivitySummary().getTotalAssessments()).isEqualTo(2);
        assertThat(res.getActivitySummary().getSubmittedCount()).isEqualTo(2);
        assertThat(res.getActivitySummary().getMissingCount()).isEqualTo(0L);

        assertThat(res.getScoreSummary().getAverageScore()).isEqualTo(60.0);
        assertThat(res.getScoreSummary().getAverageScoreRatio()).isEqualTo(0.6);
        assertThat(res.getScoreSummary().getTrend()).isEqualTo(AiScoreTrend.INSUFFICIENT_DATA);

        assertThat(res.getAssessments()).hasSize(2);
        assertThat(res.getAssessments().get(0).isSubmitted()).isTrue();
        assertThat(res.getAssessments().get(0).getScoreRatio()).isEqualTo(0.6);
        assertThat(res.getAssessments().get(0).getWeakConcepts()).containsExactlyInAnyOrder("재귀", "반복문");
        assertThat(res.getAssessments().get(1).isSubmitted()).isTrue();
        assertThat(res.getAssessments().get(1).getScore()).isNull();

        assertThat(res.getCompetencies()).hasSize(2);
        assertThat(res.getReportCriteria()).hasSize(1);
        assertThat(res.getReportCriteria().get(0).getId()).isEqualTo("builtin:CONCEPT_UNDERSTANDING");
        assertThat(res.getReportCriteria().get(0).isBuiltIn()).isTrue();
        AiCompetencyDto logic = res.getCompetencies().stream()
                .filter(c -> "logic".equals(c.getKey())).findFirst().orElseThrow();
        assertThat(logic.getLevel()).isEqualTo(AiCompetencyLevel.NEEDS_IMPROVEMENT);
        assertThat(logic.getEvidence()).isNotEmpty();
        AiCompetencyDto syntax = res.getCompetencies().stream()
                .filter(c -> "syntax".equals(c.getKey())).findFirst().orElseThrow();
        assertThat(syntax.getLevel()).isEqualTo(AiCompetencyLevel.EXCELLENT);

        List<AiEvidenceItemDto> evidence = res.getEvidence();
        assertThat(evidence).extracting(AiEvidenceItemDto::getType).containsOnly("exam", "submission");
        AiEvidenceItemDto examEv = evidence.stream().filter(e -> "exam".equals(e.getType())).findFirst().orElseThrow();
        assertThat(examEv.getRawText()).contains("기본 개념 부족");

        assertThat(res.getExistingNarrative().getSummary()).isNotBlank();
        assertThat(res.getReportWarnings()).doesNotContain("feedback_profile_missing", "feedback_profile_invalid");
    }

    @Test
    void aiContext_includesIntegratedLearningEvidenceAndSummary() {
        primeOwnerAndEnrollment();
        when(assessmentRepository.findByCourse_Id(COURSE_ID)).thenReturn(List.of());
        when(assessmentRepository.countByCourse_Id(COURSE_ID)).thenReturn(0L);
        when(examResultRepository.findByCourseIdAndUserIdWithSession(anyLong(), anyLong())).thenReturn(List.of());
        when(submissionRepository.findByCourseIdAndStudentIdWithAssessment(anyLong(), anyLong())).thenReturn(List.of());

        Lecture lecture = Lecture.builder()
                .course(course)
                .title("lecture")
                .weekNumber(1)
                .description("d")
                .build();
        ReflectionTestUtils.setField(lecture, "id", 38L);
        LearningChatSession session = LearningChatSession.builder()
                .lecture(lecture)
                .user(studentUser)
                .build();
        ReflectionTestUtils.setField(session, "id", 27L);
        Material material = Material.builder()
                .lecture(lecture)
                .displayName("m")
                .materialType("PDF")
                .filePath("p")
                .url(null)
                .uploadedBy(1L)
                .build();
        ReflectionTestUtils.setField(material, "id", 20L);

        LearningSessionEvidence quiz1 = sessionEvidence(
                "evidence-1", session, lecture, material, "QUIZ_GRADED", "OX_Problem", 0.2, false, List.of("CBR", "VBR"));
        LearningSessionEvidence quiz2 = sessionEvidence(
                "evidence-2", session, lecture, material, "QUIZ_GRADED", "OX_Problem", 1.0, true, List.of("CBR"));
        LearningSessionEvidence resolved = sessionEvidence(
                "evidence-3", session, lecture, material, "MISCONCEPTION_RESOLVED", null, null, null, List.of("VBR"));
        when(learningSessionEvidenceRepository.findTop50ByCourseIdAndStudentIdOrderByOccurredAtDescIdDesc(COURSE_ID, STUDENT_ID))
                .thenReturn(List.of(resolved, quiz2, quiz1));

        StudentAiReportContextResponse res = service.getStudentAiReportContext(COURSE_ID, STUDENT_ID);

        assertThat(res.getLearningEvidence()).hasSize(3);
        AiLearningEvidenceDto firstQuiz = res.getLearningEvidence().stream()
                .filter(e -> "evidence-2".equals(e.getEvidenceId()))
                .findFirst()
                .orElseThrow();
        assertThat(firstQuiz.getSessionId()).isEqualTo(27L);
        assertThat(firstQuiz.getLectureId()).isEqualTo(38L);
        assertThat(firstQuiz.getMaterialId()).isEqualTo(20L);
        assertThat(firstQuiz.getStudentId()).isEqualTo(STUDENT_ID);
        assertThat(firstQuiz.getQuizType()).isEqualTo("OX_Problem");

        assertThat(res.getIntegratedLearningSummary().getQuizAttemptCount()).isEqualTo(2);
        assertThat(res.getIntegratedLearningSummary().getPassCount()).isEqualTo(1);
        assertThat(res.getIntegratedLearningSummary().getFailCount()).isEqualTo(1);
        assertThat(res.getIntegratedLearningSummary().getAverageScoreRatio()).isEqualTo(0.6);
        assertThat(res.getIntegratedLearningSummary().getWeakConcepts()).containsExactly("CBR", "VBR");
        assertThat(res.getIntegratedLearningSummary().getResolvedConcepts()).containsExactly("VBR");
        assertThat(res.getIntegratedLearningSummary().getLatestActivityAt()).isEqualTo(LocalDateTime.of(2026, 1, 3, 0, 0));
    }

    @Test
    void detail_includesIntegratedLearningEvidenceAndSummary() {
        primeOwnerAndEnrollment();
        when(assessmentRepository.countByCourse_Id(COURSE_ID)).thenReturn(0L);
        when(examResultRepository.findByCourseIdAndUserIdWithSession(anyLong(), anyLong())).thenReturn(List.of());
        when(submissionRepository.findByCourseIdAndStudentIdWithAssessment(anyLong(), anyLong())).thenReturn(List.of());

        Lecture lecture = Lecture.builder()
                .course(course)
                .title("lecture")
                .weekNumber(1)
                .description("d")
                .build();
        ReflectionTestUtils.setField(lecture, "id", 38L);
        LearningChatSession session = LearningChatSession.builder()
                .lecture(lecture)
                .user(studentUser)
                .build();
        ReflectionTestUtils.setField(session, "id", 27L);

        LearningSessionEvidence quiz = sessionEvidence(
                "evidence-1", session, lecture, null, "QUIZ_GRADED", "OX_Problem", 0.5, false, List.of("CBR"));
        when(learningSessionEvidenceRepository.findTop50ByCourseIdAndStudentIdOrderByOccurredAtDescIdDesc(COURSE_ID, STUDENT_ID))
                .thenReturn(List.of(quiz));

        StudentReportDetailResponse res = service.getStudentReportDetail(COURSE_ID, STUDENT_ID);

        assertThat(res.getLearningEvidence()).hasSize(1);
        assertThat(res.getLearningEvidence().get(0).getEvidenceId()).isEqualTo("evidence-1");
        assertThat(res.getIntegratedLearningSummary().getQuizAttemptCount()).isEqualTo(1);
        assertThat(res.getIntegratedLearningSummary().getFailCount()).isEqualTo(1);
        assertThat(res.getReportCriteria()).hasSize(1);
    }

    @Test
    void detail_summarizesNestedRawIntegratedLearningEvidence() {
        primeOwnerAndEnrollment();
        when(assessmentRepository.countByCourse_Id(COURSE_ID)).thenReturn(0L);
        when(examResultRepository.findByCourseIdAndUserIdWithSession(anyLong(), anyLong())).thenReturn(List.of());
        when(submissionRepository.findByCourseIdAndStudentIdWithAssessment(anyLong(), anyLong())).thenReturn(List.of());

        Lecture lecture = Lecture.builder()
                .course(course)
                .title("lecture")
                .weekNumber(1)
                .description("d")
                .build();
        ReflectionTestUtils.setField(lecture, "id", 38L);
        LearningChatSession session = LearningChatSession.builder()
                .lecture(lecture)
                .user(studentUser)
                .build();
        ReflectionTestUtils.setField(session, "id", 27L);

        LearningSessionEvidence quiz = LearningSessionEvidence.builder()
                .evidenceId("evidence-4")
                .course(course)
                .lecture(lecture)
                .student(student)
                .session(session)
                .pageNumber(3)
                .eventType("QUIZ_GRADED")
                .evidence(Map.of(
                        "type", "learning_evidence",
                        "quiz", Map.of("quizType", "OX_Problem"),
                        "grading", Map.of("scoreRatio", 0.4, "passed", false, "wrongItems", List.of(Map.of("id", 1))),
                        "diagnosis", Map.of("weakConcepts", List.of("분수 통분"))))
                .occurredAt(LocalDateTime.of(2026, 1, 4, 0, 0))
                .build();
        LearningSessionEvidence repaired = LearningSessionEvidence.builder()
                .evidenceId("evidence-5")
                .course(course)
                .lecture(lecture)
                .student(student)
                .session(session)
                .pageNumber(3)
                .eventType("MISCONCEPTION_REPAIR_COMPLETED")
                .evidence(Map.of(
                        "type", "learning_evidence",
                        "diagnosis", Map.of("weakConcepts", List.of("분수 통분"))))
                .occurredAt(LocalDateTime.of(2026, 1, 5, 0, 0))
                .build();
        when(learningSessionEvidenceRepository.findTop50ByCourseIdAndStudentIdOrderByOccurredAtDescIdDesc(COURSE_ID, STUDENT_ID))
                .thenReturn(List.of(repaired, quiz));

        StudentReportDetailResponse res = service.getStudentReportDetail(COURSE_ID, STUDENT_ID);

        assertThat(res.getLearningEvidence()).hasSize(2);
        AiLearningEvidenceDto quizDto = res.getLearningEvidence().stream()
                .filter(e -> "evidence-4".equals(e.getEvidenceId()))
                .findFirst()
                .orElseThrow();
        assertThat(quizDto.getQuizType()).isEqualTo("OX_Problem");
        assertThat(quizDto.getScoreRatio()).isEqualTo(0.4);
        assertThat(quizDto.getPassed()).isFalse();
        assertThat(quizDto.getWeakConcepts()).containsExactly("분수 통분");
        assertThat(quizDto.getWrongItems()).hasSize(1);
        assertThat(res.getIntegratedLearningSummary().getQuizAttemptCount()).isEqualTo(1);
        assertThat(res.getIntegratedLearningSummary().getFailCount()).isEqualTo(1);
        assertThat(res.getIntegratedLearningSummary().getAverageScoreRatio()).isEqualTo(0.4);
        assertThat(res.getIntegratedLearningSummary().getWeakConcepts()).containsExactly("분수 통분");
        assertThat(res.getIntegratedLearningSummary().getResolvedConcepts()).containsExactly("분수 통분");
    }

    @Test
    void detail_marksInsufficientData_whenExamResultHasNoValidScore() {
        primeOwnerAndEnrollment();
        when(assessmentRepository.countByCourse_Id(COURSE_ID)).thenReturn(0L);
        when(submissionRepository.findByCourseIdAndStudentIdWithAssessment(anyLong(), anyLong())).thenReturn(List.of());

        ExamSession session = ExamSession.builder()
                .lecture(null).material(null).displayName(null).user(studentUser)
                .examType(ExamType.FIVE_CHOICE).targetCount(10).build();
        ExamResult resultWithoutScore = ExamResult.builder()
                .examSession(session)
                .submission(null)
                .user(studentUser)
                .build();
        ReflectionTestUtils.setField(resultWithoutScore, "id", 7L);
        ReflectionTestUtils.setField(resultWithoutScore, "completedAt", LocalDateTime.now());
        when(examResultRepository.findByCourseIdAndUserIdWithSession(COURSE_ID, STUDENT_USER_ID))
                .thenReturn(List.of(resultWithoutScore));

        StudentReportDetailResponse res = service.getStudentReportDetail(COURSE_ID, STUDENT_ID);

        assertThat(res.getActivitySummary().getExamAttemptCount()).isEqualTo(1);
        assertThat(res.getScoreSummary().getAverageScorePercent()).isNull();
        assertThat(res.getOverallScorePercent()).isNull();
        assertThat(res.getReportStatus()).isEqualTo("insufficient_data");
        assertThat(res.getHeadline()).doesNotContain("0.0");
    }

    @Test
    void aiContext_throwsForbidden_forNonOwnerTeacher() {
        Teacher other = Teacher.builder().schoolName("o").department("o").build();
        ReflectionTestUtils.setField(other, "id", OTHER_TEACHER_ID);

        when(currentUserResolver.getTeacher()).thenReturn(other);
        when(courseRepository.findById(COURSE_ID)).thenReturn(Optional.of(course));

        assertThatThrownBy(() -> service.getStudentAiReportContext(COURSE_ID, STUDENT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
    }

    @Test
    void aiContext_throwsMemberNotFound_whenStudentNotEnrolled() {
        when(currentUserResolver.getTeacher()).thenReturn(owner);
        when(courseRepository.findById(COURSE_ID)).thenReturn(Optional.of(course));
        when(enrollmentRepository.findByCourseIdAndStudentIdWithUser(COURSE_ID, STUDENT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStudentAiReportContext(COURSE_ID, STUDENT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    void aiContext_returnsEmptyShape_whenNoActivity() {
        primeOwnerAndEnrollment();
        Assessment a1 = assessment(1L, "퀴즈1");
        when(assessmentRepository.findByCourse_Id(COURSE_ID)).thenReturn(List.of(a1));
        when(assessmentRepository.countByCourse_Id(COURSE_ID)).thenReturn(1L);
        when(examResultRepository.findByCourseIdAndUserIdWithSession(anyLong(), anyLong())).thenReturn(List.of());
        when(submissionRepository.findByCourseIdAndStudentIdWithAssessment(anyLong(), anyLong())).thenReturn(List.of());

        StudentAiReportContextResponse res = service.getStudentAiReportContext(COURSE_ID, STUDENT_ID);

        assertThat(res.getAssessments()).hasSize(1);
        assertThat(res.getAssessments().get(0).isSubmitted()).isFalse();
        assertThat(res.getActivitySummary().getMissingCount()).isEqualTo(1L);
        assertThat(res.getScoreSummary().getTrend()).isEqualTo(AiScoreTrend.INSUFFICIENT_DATA);
        assertThat(res.getEvidence()).isEmpty();
        assertThat(res.getCompetencies()).isEmpty();
    }

    @Test
    void aiContext_addsWarning_whenUserFeedbackJsonMissing() {
        primeOwnerAndEnrollment();
        when(assessmentRepository.findByCourse_Id(COURSE_ID)).thenReturn(List.of());
        when(assessmentRepository.countByCourse_Id(COURSE_ID)).thenReturn(0L);

        ExamResult er = examResultWith(1L, 80.0, null, "총평");
        when(examResultRepository.findByCourseIdAndUserIdWithSession(anyLong(), anyLong())).thenReturn(List.of(er));
        when(submissionRepository.findByCourseIdAndStudentIdWithAssessment(anyLong(), anyLong())).thenReturn(List.of());

        StudentAiReportContextResponse res = service.getStudentAiReportContext(COURSE_ID, STUDENT_ID);

        assertThat(res.getReportWarnings()).contains("feedback_profile_missing");
    }

    @Test
    void aiContext_addsWarning_whenEvaluationItemsMalformed() {
        primeOwnerAndEnrollment();
        when(assessmentRepository.findByCourse_Id(COURSE_ID)).thenReturn(List.of());
        when(assessmentRepository.countByCourse_Id(COURSE_ID)).thenReturn(0L);

        Map<String, Object> badJson = Map.of("evaluationItems", "not-a-list");
        ExamResult er = examResultWith(1L, 80.0, badJson, "총평");
        when(examResultRepository.findByCourseIdAndUserIdWithSession(anyLong(), anyLong())).thenReturn(List.of(er));
        when(submissionRepository.findByCourseIdAndStudentIdWithAssessment(anyLong(), anyLong())).thenReturn(List.of());

        StudentAiReportContextResponse res = service.getStudentAiReportContext(COURSE_ID, STUDENT_ID);

        assertThat(res.getReportWarnings()).contains("feedback_profile_invalid");
    }
}
