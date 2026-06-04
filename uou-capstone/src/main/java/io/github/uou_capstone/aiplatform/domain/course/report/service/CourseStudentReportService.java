package io.github.uou_capstone.aiplatform.domain.course.report.service;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.common.web.PageableSupport;
import io.github.uou_capstone.aiplatform.domain.assessment.entity.Assessment;
import io.github.uou_capstone.aiplatform.domain.assessment.repository.AssessmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.entity.Enrollment;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ActivitySummaryDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.CompetencyDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.CompetencyStatus;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.CourseInfoDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.EvidenceDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.NarrativeReportDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ReportStatus;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ScoreSummaryDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.StudentInfoDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.StudentReportDetailResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.StudentReportListItem;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.SubmissionSummaryDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.AiActivitySummaryDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.AiAssessmentItemDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.AiCompetencyDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.AiCompetencyLevel;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.AiCourseInfoDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.AiEvidenceItemDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.AiIntegratedLearningSummaryDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.AiLearningEvidenceDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.AiNarrativeDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.AiScoreSummaryDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.AiScoreTrend;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.AiStudentInfoDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.StudentAiReportContextResponse;
import io.github.uou_capstone.aiplatform.domain.course.repository.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamResult;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamResultRepository;
import io.github.uou_capstone.aiplatform.domain.learning.entity.LearningSessionEvidence;
import io.github.uou_capstone.aiplatform.domain.learning.repository.LearningSessionEvidenceRepository;
import io.github.uou_capstone.aiplatform.domain.submission.entity.Submission;
import io.github.uou_capstone.aiplatform.domain.submission.entity.SubmissionStatus;
import io.github.uou_capstone.aiplatform.domain.submission.repository.SubmissionRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 강의실 학생 리포트 — 리스트/상세 집계.
 *
 * 데이터 원본:
 *  - Enrollment   : 강의실 학생 목록
 *  - ExamResult   : userFeedbackJson.evaluationItems 의 score / evaluationDetails 로 역량 집계
 *  - Submission   : 활동 보조 지표 (역량 점수에는 직접 합산하지 않음)
 *  - Assessment   : 강의실 평가 총수 — 미제출 카운트용
 *
 * 별도 저장 테이블 없이 조회 시점에 집계.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourseStudentReportService {

    private static final int MAX_EVIDENCE = 10;
    private static final int RECENT_TREND_SIZE = 3;
    private static final int NARRATIVE_LIST_SIZE = 2;

    private static final int MAX_AI_EVIDENCE = 20;
    private static final double AI_TREND_DELTA = 5.0;
    private static final double AI_EXCELLENT_THRESHOLD = 90.0;
    private static final double AI_WEAK_CONCEPT_SCORE_THRESHOLD = 70.0;
    private static final Set<String> RESOLVED_INTERVENTION_EVENTS = Set.of(
            "INTERVENTION_RESOLVED", "MISCONCEPTION_RESOLVED", "MISCONCEPTION_REPAIR_COMPLETED", "RESOLVED");

    private static final double STRONG_THRESHOLD = 85.0;
    private static final double WATCH_THRESHOLD = 70.0;
    private static final double EXCELLING_AVG_THRESHOLD = 90.0;
    private static final double EXCELLING_COMPETENCY_THRESHOLD = 80.0;
    private static final double NEEDS_ATTENTION_AVG_THRESHOLD = 70.0;

    private static final String DEFAULT_COMPETENCY_KEY = "default";
    private static final String DEFAULT_COMPETENCY_LABEL = "문항 수행";

    static final Set<String> REPORT_SORT_WHITELIST = Set.of(
            "name", "averageScore", "latestActivity", "reportStatus");
    private static final Sort REPORT_DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "name");

    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final ExamResultRepository examResultRepository;
    private final SubmissionRepository submissionRepository;
    private final AssessmentRepository assessmentRepository;
    private final LearningSessionEvidenceRepository learningSessionEvidenceRepository;
    private final CurrentUserResolver currentUserResolver;

    @Transactional(readOnly = true)
    public PageResponse<StudentReportListItem> getStudentReportList(Long courseId,
                                                                    String q,
                                                                    String statusFilter,
                                                                    Pageable rawPageable) {
        loadCourseAsOwner(courseId);
        Pageable pageable = PageableSupport.validate(rawPageable, REPORT_SORT_WHITELIST, REPORT_DEFAULT_SORT);
        return getStudentReportListInternal(courseId, q, statusFilter, pageable);
    }

    @Transactional(readOnly = true)
    public PageResponse<StudentReportListItem> getStudentReportListForClassroomAnalysis(Long courseId,
                                                                                        Pageable pageable) {
        loadCourseAsOwner(courseId);
        return getStudentReportListInternal(courseId, null, null, pageable);
    }

    private PageResponse<StudentReportListItem> getStudentReportListInternal(Long courseId,
                                                                            String q,
                                                                            String statusFilter,
                                                                            Pageable pageable) {
        List<Enrollment> enrollments = enrollmentRepository.findByCourseIdWithStudentUser(courseId);
        if (enrollments.isEmpty()) {
            return PageResponse.empty(pageable);
        }

        List<Long> userIds = enrollments.stream()
                .map(e -> e.getStudent().getUser().getId())
                .toList();
        List<Long> studentIds = enrollments.stream()
                .map(e -> e.getStudent().getId())
                .toList();

        Map<Long, List<ExamResult>> examResultsByUserId = examResultRepository
                .findByCourseIdAndUserIdsWithSession(courseId, userIds)
                .stream()
                .collect(Collectors.groupingBy(er -> er.getUser().getId()));

        Map<Long, List<Submission>> submissionsByStudentId = submissionRepository
                .findByCourseIdAndStudentIdsWithAssessment(courseId, studentIds)
                .stream()
                .collect(Collectors.groupingBy(s -> s.getStudent().getId()));

        List<StudentReportListItem> items = enrollments.stream()
                .map(enrollment -> buildListItem(
                        enrollment,
                        examResultsByUserId.getOrDefault(enrollment.getStudent().getUser().getId(), List.of()),
                        submissionsByStudentId.getOrDefault(enrollment.getStudent().getId(), List.of())
                ))
                .collect(Collectors.toCollection(ArrayList::new));

        applyFilters(items, q, statusFilter);
        items.sort(buildItemComparator(pageable.getSort()));

        return PageResponse.ofSlice(items, pageable);
    }

    @Transactional(readOnly = true)
    public StudentReportDetailResponse getStudentReportDetail(Long courseId, Long studentId) {
        Course course = loadCourseAsOwner(courseId);

        Enrollment enrollment = enrollmentRepository
                .findByCourseIdAndStudentIdWithUser(courseId, studentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

        Student student = enrollment.getStudent();
        User studentUser = student.getUser();

        List<ExamResult> examResults = examResultRepository
                .findByCourseIdAndUserIdWithSession(courseId, studentUser.getId());
        List<Submission> submissions = submissionRepository
                .findByCourseIdAndStudentIdWithAssessment(courseId, student.getId());

        List<String> warnings = new ArrayList<>();

        ScoreSummaryDto scoreSummary = computeScoreSummary(examResults);
        List<CompetencyDto> competencies = computeCompetencies(examResults, warnings);
        ActivitySummaryDto activitySummary = computeActivitySummary(examResults, submissions);
        SubmissionSummaryDto submissionSummary = computeSubmissionSummary(courseId, submissions);
        List<EvidenceDto> evidence = buildEvidence(examResults, submissions);
        List<LearningSessionEvidence> sessionEvidence =
                learningSessionEvidenceRepository.findTop50ByCourseIdAndStudentIdOrderByOccurredAtDescIdDesc(
                        courseId, student.getId());
        List<AiLearningEvidenceDto> learningEvidence = sessionEvidence.stream()
                .map(this::toAiLearningEvidence)
                .toList();
        AiIntegratedLearningSummaryDto integratedLearningSummary =
                buildIntegratedLearningSummary(sessionEvidence);
        ReportStatus reportStatus = computeReportStatus(examResults.size(), scoreSummary, competencies);
        NarrativeReportDto narrative = buildNarrative(scoreSummary, competencies, reportStatus);
        LocalDateTime reportTimestamp = activitySummary.getLatestActivityAt();

        return StudentReportDetailResponse.builder()
                .student(StudentInfoDto.builder()
                        .studentId(student.getId())
                        .userId(studentUser.getId())
                        .studentName(studentUser.getFullName())
                        .email(studentUser.getEmail())
                        .build())
                .course(CourseInfoDto.builder()
                        .courseId(course.getId())
                        .title(course.getTitle())
                        .build())
                .activitySummary(activitySummary)
                .scoreSummary(scoreSummary)
                .competencies(competencies)
                .submissionSummary(submissionSummary)
                .evidence(evidence)
                .integratedLearningSummary(integratedLearningSummary)
                .learningEvidence(learningEvidence)
                .narrativeReport(narrative)
                .overallScorePercent(scoreSummary.getAverageScorePercent())
                .headline(buildHeadline(scoreSummary, reportStatus))
                .summaryBullets(buildSummaryBullets(activitySummary, scoreSummary, submissionSummary, reportStatus))
                .strengths(narrative.getStrengths())
                .improvementPoints(narrative.getImprovements())
                .coachingInsights(buildCoachingInsights(competencies, submissionSummary, reportStatus))
                .recommendedActions(narrative.getNextSteps())
                .generatedAt(reportTimestamp)
                .updatedAt(reportTimestamp)
                .reportStatus(reportStatus.value())
                .reportWarnings(warnings)
                .build();
    }

    // ===========================================================
    // AI Context — FastAPI POST /api/v3/report/student/analyze 입력 DTO
    // ===========================================================

    /**
     * 학생 한 명의 AI 분석용 Context. 기존 상세 리포트와 별도 응답 shape 을 사용한다.
     *
     * - Spring 은 FastAPI 를 호출하지 않는다. FastAPI 가 이 DTO 를 받아 자체 분석.
     * - 권한/소유 검증은 기존 학생 상세 리포트와 동일.
     */
    @Transactional(readOnly = true)
    public StudentAiReportContextResponse getStudentAiReportContext(Long courseId, Long studentId) {
        Course course = loadCourseAsOwner(courseId);

        Enrollment enrollment = enrollmentRepository
                .findByCourseIdAndStudentIdWithUser(courseId, studentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

        Student student = enrollment.getStudent();
        User studentUser = student.getUser();

        List<ExamResult> examResults = examResultRepository
                .findByCourseIdAndUserIdWithSession(courseId, studentUser.getId());
        List<Submission> submissions = submissionRepository
                .findByCourseIdAndStudentIdWithAssessment(courseId, student.getId());

        List<String> warnings = new ArrayList<>();

        ScoreSummaryDto baseScore = computeScoreSummary(examResults);
        AiScoreSummaryDto scoreSummary = toAiScoreSummary(baseScore, examResults);

        long totalAssessments = assessmentRepository.countByCourse_Id(courseId);
        AiActivitySummaryDto activitySummary = AiActivitySummaryDto.builder()
                .totalAssessments(totalAssessments)
                .submittedCount(submissions.size())
                .missingCount(Math.max(0L, totalAssessments - submissions.size()))
                .latestSubmittedAt(submissions.stream()
                        .map(Submission::getCreatedAt)
                        .filter(Objects::nonNull)
                        .max(Comparator.naturalOrder())
                        .orElse(null))
                .build();

        List<AiAssessmentItemDto> assessments = buildAiAssessments(courseId, submissions, warnings);

        List<CompetencyDto> baseCompetencies = computeCompetencies(examResults, warnings);
        List<AiCompetencyDto> competencies = toAiCompetencies(baseCompetencies, examResults);

        List<AiEvidenceItemDto> evidence = buildAiEvidence(examResults, submissions);
        List<LearningSessionEvidence> sessionEvidence =
                learningSessionEvidenceRepository.findTop50ByCourseIdAndStudentIdOrderByOccurredAtDescIdDesc(
                        courseId, student.getId());
        List<AiLearningEvidenceDto> learningEvidence = sessionEvidence.stream()
                .map(this::toAiLearningEvidence)
                .toList();
        AiIntegratedLearningSummaryDto integratedLearningSummary =
                buildIntegratedLearningSummary(sessionEvidence);

        ReportStatus reportStatus = computeReportStatus(examResults.size(), baseScore, baseCompetencies);
        NarrativeReportDto baseNarrative = buildNarrative(baseScore, baseCompetencies, reportStatus);
        AiNarrativeDto narrative = AiNarrativeDto.builder()
                .summary(baseNarrative.getSummary())
                .strengths(baseNarrative.getStrengths())
                .weaknesses(baseNarrative.getImprovements())
                .build();

        return StudentAiReportContextResponse.builder()
                .course(AiCourseInfoDto.builder()
                        .courseId(course.getId())
                        .courseName(course.getTitle())
                        .teacherId(course.getTeacher() != null ? course.getTeacher().getId() : null)
                        .build())
                .student(AiStudentInfoDto.builder()
                        .studentId(student.getId())
                        .studentName(studentUser.getFullName())
                        .enrollmentStatus(enrollment.getStatus() != null ? enrollment.getStatus().name() : null)
                        .build())
                .activitySummary(activitySummary)
                .scoreSummary(scoreSummary)
                .assessments(assessments)
                .competencies(competencies)
                .evidence(evidence)
                .learningEvidence(learningEvidence)
                .integratedLearningSummary(integratedLearningSummary)
                .existingNarrative(narrative)
                .reportWarnings(warnings)
                .build();
    }

    private AiLearningEvidenceDto toAiLearningEvidence(LearningSessionEvidence evidence) {
        return AiLearningEvidenceDto.builder()
                .evidenceId(evidence.getEvidenceId())
                .courseId(evidence.getCourse() != null ? evidence.getCourse().getId() : null)
                .lectureId(evidence.getLecture() != null ? evidence.getLecture().getId() : null)
                .materialId(evidence.getMaterial() != null ? evidence.getMaterial().getId() : null)
                .studentId(evidence.getStudent() != null ? evidence.getStudent().getId() : null)
                .sessionId(evidence.getSession() != null ? evidence.getSession().getId() : null)
                .pageNumber(evidence.getPageNumber())
                .eventType(evidence.getEventType())
                .quizType(effectiveQuizType(evidence))
                .scoreRatio(effectiveScoreRatio(evidence))
                .passed(effectivePassed(evidence))
                .weakConcepts(effectiveWeakConcepts(evidence))
                .wrongItems(effectiveWrongItems(evidence))
                .evidence(evidence.getEvidence() == null ? Map.of() : evidence.getEvidence())
                .occurredAt(evidence.getOccurredAt())
                .build();
    }

    private AiIntegratedLearningSummaryDto buildIntegratedLearningSummary(List<LearningSessionEvidence> evidence) {
        long quizAttemptCount = evidence.stream()
                .filter(e -> firstNonBlank(effectiveQuizType(e)) != null || effectiveScoreRatio(e) != null)
                .count();
        long passCount = evidence.stream()
                .filter(e -> Boolean.TRUE.equals(effectivePassed(e)))
                .count();
        long failCount = evidence.stream()
                .filter(e -> Boolean.FALSE.equals(effectivePassed(e)))
                .count();

        List<Double> scoreRatios = evidence.stream()
                .map(this::effectiveScoreRatio)
                .filter(Objects::nonNull)
                .toList();
        Double averageScoreRatio = scoreRatios.isEmpty()
                ? null
                : round3(scoreRatios.stream().mapToDouble(Double::doubleValue).average().orElse(0.0));

        Map<String, Long> weakConceptCounts = new LinkedHashMap<>();
        Map<String, Long> resolvedConceptCounts = new LinkedHashMap<>();
        for (LearningSessionEvidence item : evidence) {
            for (String concept : effectiveWeakConcepts(item)) {
                if (concept != null && !concept.isBlank()) {
                    if (RESOLVED_INTERVENTION_EVENTS.contains(item.getEventType())) {
                        resolvedConceptCounts.merge(concept, 1L, Long::sum);
                    } else {
                        weakConceptCounts.merge(concept, 1L, Long::sum);
                    }
                }
            }
        }
        List<String> weakConcepts = weakConceptCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
        List<String> resolvedConcepts = resolvedConceptCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
        LocalDateTime latestActivityAt = evidence.stream()
                .map(LearningSessionEvidence::getOccurredAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        return AiIntegratedLearningSummaryDto.builder()
                .quizAttemptCount(quizAttemptCount)
                .passCount(passCount)
                .failCount(failCount)
                .averageScoreRatio(averageScoreRatio)
                .weakConcepts(weakConcepts)
                .resolvedConcepts(resolvedConcepts)
                .latestActivityAt(latestActivityAt)
                .build();
    }

    private String effectiveQuizType(LearningSessionEvidence evidence) {
        Map<?, ?> quiz = nestedMap(evidence.getEvidence(), "quiz");
        return firstNonBlank(
                evidence.getQuizType(),
                asString(rawValue(evidence, "quizType")),
                asString(rawValue(evidence, "quiz_type")),
                asString(quiz.get("quizType")),
                asString(quiz.get("quiz_type"))
        );
    }

    private Double effectiveScoreRatio(LearningSessionEvidence evidence) {
        Map<?, ?> grading = nestedMap(evidence.getEvidence(), "grading");
        Double value = firstNonNull(
                evidence.getScoreRatio(),
                asDouble(rawValue(evidence, "scoreRatio")),
                asDouble(rawValue(evidence, "score_ratio")),
                asDouble(grading.get("scoreRatio")),
                asDouble(grading.get("score_ratio"))
        );
        return value == null ? null : round3(value);
    }

    private Boolean effectivePassed(LearningSessionEvidence evidence) {
        Map<?, ?> grading = nestedMap(evidence.getEvidence(), "grading");
        return firstNonNull(
                evidence.getPassed(),
                asBoolean(rawValue(evidence, "passed")),
                asBoolean(grading.get("passed"))
        );
    }

    private List<String> effectiveWeakConcepts(LearningSessionEvidence evidence) {
        if (evidence.getWeakConcepts() != null && !evidence.getWeakConcepts().isEmpty()) {
            return evidence.getWeakConcepts();
        }
        Map<?, ?> diagnosis = nestedMap(evidence.getEvidence(), "diagnosis");
        return asStringList(firstNonNull(
                rawValue(evidence, "weakConcepts"),
                rawValue(evidence, "weak_concepts"),
                diagnosis.get("weakConcepts"),
                diagnosis.get("weak_concepts")
        ));
    }

    private List<Object> effectiveWrongItems(LearningSessionEvidence evidence) {
        if (evidence.getWrongItems() != null && !evidence.getWrongItems().isEmpty()) {
            return evidence.getWrongItems();
        }
        Map<?, ?> grading = nestedMap(evidence.getEvidence(), "grading");
        return asObjectList(firstNonNull(
                rawValue(evidence, "wrongItems"),
                rawValue(evidence, "wrong_items"),
                rawValue(evidence, "missedQuestions"),
                rawValue(evidence, "missed_questions"),
                grading.get("wrongItems"),
                grading.get("wrong_items"),
                grading.get("missedQuestions"),
                grading.get("missed_questions")
        ));
    }

    private Object rawValue(LearningSessionEvidence evidence, String key) {
        return evidence.getEvidence() == null ? null : evidence.getEvidence().get(key);
    }

    private Map<?, ?> nestedMap(Map<String, Object> source, String key) {
        if (source == null) {
            return Map.of();
        }
        Object value = source.get(key);
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private AiScoreSummaryDto toAiScoreSummary(ScoreSummaryDto base, List<ExamResult> examResults) {
        Double avg = base.getAverageScorePercent();
        Double ratio = avg == null ? null : round3(avg / 100.0);
        List<Double> recentRatio = base.getRecentTrendPercent() == null
                ? List.of()
                : base.getRecentTrendPercent().stream()
                        .map(p -> p == null ? null : round3(p / 100.0))
                        .filter(Objects::nonNull)
                        .toList();

        return AiScoreSummaryDto.builder()
                .averageScore(avg)
                .averageScoreRatio(ratio)
                .highestScore(base.getHighestScorePercent())
                .lowestScore(base.getLowestScorePercent())
                .recentTrend(recentRatio)
                .trend(computeTrend(examResults))
                .build();
    }

    private AiScoreTrend computeTrend(List<ExamResult> examResults) {
        List<Double> ascPercents = examResults.stream()
                .sorted(Comparator.comparing(this::resultTimestamp,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toScorePercent)
                .filter(Objects::nonNull)
                .toList();
        if (ascPercents.size() < 2) {
            return AiScoreTrend.INSUFFICIENT_DATA;
        }
        double delta = ascPercents.get(ascPercents.size() - 1) - ascPercents.get(0);
        if (delta >= AI_TREND_DELTA) {
            return AiScoreTrend.IMPROVING;
        }
        if (delta <= -AI_TREND_DELTA) {
            return AiScoreTrend.DECLINING;
        }
        return AiScoreTrend.STABLE;
    }

    private List<AiAssessmentItemDto> buildAiAssessments(Long courseId,
                                                         List<Submission> studentSubmissions,
                                                         List<String> warnings) {
        List<Assessment> all = assessmentRepository.findByCourse_Id(courseId).stream()
                .sorted(Comparator.comparing(Assessment::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        Map<Long, Submission> byAssessmentId = studentSubmissions.stream()
                .filter(s -> s.getAssessment() != null)
                .collect(Collectors.toMap(
                        s -> s.getAssessment().getId(),
                        s -> s,
                        (a, b) -> a.getCreatedAt() != null && b.getCreatedAt() != null
                                && a.getCreatedAt().isAfter(b.getCreatedAt()) ? a : b));

        List<AiAssessmentItemDto> items = new ArrayList<>(all.size());
        for (Assessment assessment : all) {
            Submission submission = byAssessmentId.get(assessment.getId());
            if (submission == null) {
                items.add(AiAssessmentItemDto.builder()
                        .assessmentId(assessment.getId())
                        .title(assessment.getTitle())
                        .submitted(false)
                        .weakConcepts(List.of())
                        .build());
                continue;
            }

            ExamResult result = submission.getExamResult();
            BigDecimal score = result != null ? result.getTotalScore() : null;
            BigDecimal max = result != null ? result.getMaxScore() : null;
            Double scoreRatio = (score != null && max != null && max.signum() != 0)
                    ? round3(score.doubleValue() / max.doubleValue())
                    : null;
            String feedback = result != null ? result.getOverallFeedback() : null;
            List<String> weakConcepts = result != null
                    ? extractWeakConceptsFromExamResult(result, warnings)
                    : List.of();

            items.add(AiAssessmentItemDto.builder()
                    .assessmentId(assessment.getId())
                    .title(assessment.getTitle())
                    .submitted(true)
                    .score(score)
                    .maxScore(max)
                    .scoreRatio(scoreRatio)
                    .submittedAt(submission.getCreatedAt())
                    .feedback(feedback)
                    .weakConcepts(weakConcepts)
                    .build());
        }
        return items;
    }

    /**
     * userFeedbackJson.evaluationItems 중 score &lt; 70 또는 correct == false 인 항목의
     * evaluationDetails 에서 약점 개념 후보를 best-effort 추출한다.
     */
    private List<String> extractWeakConceptsFromExamResult(ExamResult result, List<String> warnings) {
        Map<String, Object> profile = result.getUserFeedbackJson();
        if (profile == null) {
            if (warnings != null) {
                addWarningOnce(warnings, "feedback_profile_missing");
            }
            return List.of();
        }
        Object itemsRaw = profile.get("evaluationItems");
        if (!(itemsRaw instanceof List<?> items)) {
            if (warnings != null) {
                addWarningOnce(warnings, "feedback_profile_invalid");
            }
            return List.of();
        }

        LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
        for (Object itemRaw : items) {
            if (!(itemRaw instanceof Map<?, ?> itemMap)) {
                continue;
            }
            if (!isWeakItem(itemMap)) {
                continue;
            }
            Object detailsRaw = itemMap.get("evaluationDetails");
            if (!(detailsRaw instanceof Map<?, ?> details)) {
                continue;
            }
            for (String concept : collectConceptCandidates(details)) {
                if (concept != null && !concept.isBlank()) {
                    seen.putIfAbsent(concept.trim(), Boolean.TRUE);
                }
            }
        }
        return new ArrayList<>(seen.keySet());
    }

    private boolean isWeakItem(Map<?, ?> itemMap) {
        Object correctRaw = itemMap.get("correct");
        if (correctRaw instanceof Boolean correct && !correct) {
            return true;
        }
        Double score = extractItemScore(itemMap);
        return score != null && score < AI_WEAK_CONCEPT_SCORE_THRESHOLD;
    }

    private List<String> collectConceptCandidates(Map<?, ?> details) {
        List<String> out = new ArrayList<>();
        Object weakRaw = details.get("weakConcepts");
        if (weakRaw instanceof List<?> list) {
            for (Object v : list) {
                String s = asString(v);
                if (s != null) {
                    out.add(s);
                }
            }
        } else if (weakRaw instanceof String s && !s.isBlank()) {
            out.add(s);
        }
        addIfPresent(out, details, "concept");
        addIfPresent(out, details, "competency");
        addIfPresent(out, details, "category");
        addIfPresent(out, details, "skill");
        return out;
    }

    private void addIfPresent(List<String> out, Map<?, ?> details, String key) {
        String s = asString(details.get(key));
        if (s != null && !s.isBlank()) {
            out.add(s);
        }
    }

    private List<AiCompetencyDto> toAiCompetencies(List<CompetencyDto> base, List<ExamResult> examResults) {
        if (base.isEmpty()) {
            return List.of();
        }
        Map<String, List<AiEvidenceItemDto>> evidenceByKey = collectCompetencyEvidence(examResults);

        return base.stream()
                .map(c -> AiCompetencyDto.builder()
                        .key(c.getKey())
                        .label(c.getLabel())
                        .score(c.getAverageScorePercent())
                        .level(mapCompetencyLevel(c))
                        .latestFeedback(c.getLatestFeedback())
                        .evidenceCount(c.getEvidenceCount())
                        .evidence(evidenceByKey.getOrDefault(c.getKey(), List.of()))
                        .build())
                .toList();
    }

    private AiCompetencyLevel mapCompetencyLevel(CompetencyDto c) {
        String status = c.getStatus();
        Double score = c.getAverageScorePercent();
        if (CompetencyStatus.INSUFFICIENT_DATA.value().equals(status)) {
            return AiCompetencyLevel.INSUFFICIENT_DATA;
        }
        if (CompetencyStatus.STRONG.value().equals(status)) {
            return score != null && score >= AI_EXCELLENT_THRESHOLD
                    ? AiCompetencyLevel.EXCELLENT
                    : AiCompetencyLevel.GOOD;
        }
        if (CompetencyStatus.WATCH.value().equals(status)) {
            return AiCompetencyLevel.WATCH;
        }
        return AiCompetencyLevel.NEEDS_IMPROVEMENT;
    }

    /**
     * 역량 키별로 등장한 ExamResult 목록을 evidence 항목으로 누적.
     * 같은 (competencyKey, examResultId) 쌍이 중복 등장해도 한 번만 들어간다.
     */
    private Map<String, List<AiEvidenceItemDto>> collectCompetencyEvidence(List<ExamResult> examResults) {
        Map<String, LinkedHashMap<Long, AiEvidenceItemDto>> acc = new LinkedHashMap<>();

        for (ExamResult result : examResults) {
            Map<String, Object> profile = result.getUserFeedbackJson();
            if (profile == null) continue;
            Object itemsRaw = profile.get("evaluationItems");
            if (!(itemsRaw instanceof List<?> items)) continue;

            for (Object itemRaw : items) {
                if (!(itemRaw instanceof Map<?, ?> itemMap)) continue;
                if (extractItemScore(itemMap) == null) continue;

                String key = extractCompetencyKey(itemMap);
                acc.computeIfAbsent(key, k -> new LinkedHashMap<>())
                        .putIfAbsent(result.getId(), buildExamEvidence(result));
            }
        }

        Map<String, List<AiEvidenceItemDto>> out = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashMap<Long, AiEvidenceItemDto>> e : acc.entrySet()) {
            out.put(e.getKey(), List.copyOf(e.getValue().values()));
        }
        return out;
    }

    private List<AiEvidenceItemDto> buildAiEvidence(List<ExamResult> examResults, List<Submission> submissions) {
        List<AiEvidenceItemDto> all = new ArrayList<>();
        for (ExamResult er : examResults) {
            all.add(buildExamEvidence(er));
        }

        for (Submission s : submissions) {
            if (s.getExamResult() != null) {
                continue; // ExamResult 가 이미 evidence 로 들어가므로 중복 방지
            }
            String title = s.getAssessment() != null ? s.getAssessment().getTitle() : "(과제)";
            String status = s.getStatus() != null ? s.getStatus().name() : "UNKNOWN";
            all.add(AiEvidenceItemDto.builder()
                    .type("submission")
                    .sourceId(s.getId())
                    .summary("%s 제출 — %s".formatted(title, status))
                    .rawText("%s 상태 제출".formatted(status))
                    .occurredAt(s.getCreatedAt())
                    .build());
        }

        return all.stream()
                .sorted(Comparator.comparing(AiEvidenceItemDto::getOccurredAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_AI_EVIDENCE)
                .toList();
    }

    private AiEvidenceItemDto buildExamEvidence(ExamResult result) {
        String examType = result.getExamSession() != null && result.getExamSession().getExamType() != null
                ? result.getExamSession().getExamType().name()
                : null;
        String lectureTitle = result.getExamSession() != null && result.getExamSession().getLecture() != null
                ? result.getExamSession().getLecture().getTitle()
                : null;
        Double percent = toScorePercent(result);

        StringBuilder summary = new StringBuilder();
        if (lectureTitle != null) summary.append(lectureTitle).append(" ");
        if (examType != null) summary.append(examType).append(" ");
        summary.append("응시");
        if (percent != null) summary.append(" — %.1f%%".formatted(percent));

        return AiEvidenceItemDto.builder()
                .type("exam")
                .sourceId(result.getId())
                .summary(summary.toString().trim())
                .rawText(buildExamRawText(result))
                .occurredAt(resultTimestamp(result))
                .build();
    }

    private String buildExamRawText(ExamResult result) {
        Map<String, Object> profile = result.getUserFeedbackJson();
        if (profile != null) {
            Object itemsRaw = profile.get("evaluationItems");
            if (itemsRaw instanceof List<?> items) {
                List<String> feedbacks = new ArrayList<>();
                for (Object itemRaw : items) {
                    if (itemRaw instanceof Map<?, ?> itemMap) {
                        String fb = asString(itemMap.get("feedback"));
                        if (fb != null && !fb.isBlank()) {
                            feedbacks.add(fb);
                        }
                    }
                }
                if (!feedbacks.isEmpty()) {
                    return String.join("\n", feedbacks);
                }
            }
        }
        if (result.getOverallFeedback() != null && !result.getOverallFeedback().isBlank()) {
            return result.getOverallFeedback();
        }
        BigDecimal total = result.getTotalScore();
        BigDecimal max = result.getMaxScore();
        if (total != null && max != null) {
            return "%s/%s 점 응시 결과".formatted(total.toPlainString(), max.toPlainString());
        }
        return "응시 결과 (피드백 없음)";
    }

    // ===========================================================
    // 권한 검증
    // ===========================================================

    private Course loadCourseAsOwner(Long courseId) {
        Teacher currentTeacher = currentUserResolver.getTeacher();
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COURSE_NOT_FOUND));

        if (!course.getTeacher().getId().equals(currentTeacher.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return course;
    }

    // ===========================================================
    // 학생 카드 (리스트용)
    // ===========================================================

    private StudentReportListItem buildListItem(Enrollment enrollment,
                                                List<ExamResult> examResults,
                                                List<Submission> submissions) {
        Student student = enrollment.getStudent();
        User user = student.getUser();

        ScoreSummaryDto scoreSummary = computeScoreSummary(examResults);
        List<CompetencyDto> competencies = computeCompetencies(examResults, new ArrayList<>());
        ReportStatus reportStatus = computeReportStatus(examResults.size(), scoreSummary, competencies);

        LocalDateTime latestActivity = computeLatestActivity(examResults, submissions);

        String topStrength = competencies.stream()
                .filter(c -> CompetencyStatus.STRONG.value().equals(c.getStatus()))
                .max(Comparator.comparingDouble(c -> c.getAverageScorePercent() == null ? 0.0 : c.getAverageScorePercent()))
                .map(CompetencyDto::getLabel)
                .orElse(null);

        String topImprovement = competencies.stream()
                .filter(c -> CompetencyStatus.NEEDS_IMPROVEMENT.value().equals(c.getStatus()))
                .min(Comparator.comparingDouble(c -> c.getAverageScorePercent() == null ? 0.0 : c.getAverageScorePercent()))
                .map(CompetencyDto::getLabel)
                .orElse(null);

        return StudentReportListItem.builder()
                .studentId(student.getId())
                .userId(user.getId())
                .studentName(user.getFullName())
                .averageScorePercent(scoreSummary.getAverageScorePercent())
                .examAttemptCount(examResults.size())
                .submissionCount(submissions.size())
                .latestActivityAt(latestActivity)
                .reportStatus(reportStatus.value())
                .topStrengthLabel(topStrength)
                .topImprovementLabel(topImprovement)
                .build();
    }

    // ===========================================================
    // 점수 집계
    // ===========================================================

    private ScoreSummaryDto computeScoreSummary(List<ExamResult> examResults) {
        List<Double> percents = examResults.stream()
                .sorted(Comparator.comparing(this::resultTimestamp).reversed())
                .map(this::toScorePercent)
                .filter(Objects::nonNull)
                .toList();

        if (percents.isEmpty()) {
            return ScoreSummaryDto.builder()
                    .recentTrendPercent(List.of())
                    .build();
        }

        double avg = percents.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double highest = percents.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double lowest = percents.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);

        List<Double> recent = percents.size() <= RECENT_TREND_SIZE
                ? percents
                : percents.subList(0, RECENT_TREND_SIZE);

        return ScoreSummaryDto.builder()
                .averageScorePercent(round1(avg))
                .highestScorePercent(round1(highest))
                .lowestScorePercent(round1(lowest))
                .recentTrendPercent(recent.stream().map(this::round1).toList())
                .build();
    }

    private Double toScorePercent(ExamResult result) {
        BigDecimal total = result.getTotalScore();
        BigDecimal max = result.getMaxScore();
        if (total == null || max == null || max.signum() == 0) {
            return null;
        }
        return total.doubleValue() / max.doubleValue() * 100.0;
    }

    private LocalDateTime resultTimestamp(ExamResult result) {
        LocalDateTime completed = result.getCompletedAt();
        return completed != null ? completed : result.getCreatedAt();
    }

    // ===========================================================
    // 역량 집계 — userFeedbackJson.evaluationItems
    // ===========================================================

    private List<CompetencyDto> computeCompetencies(List<ExamResult> examResults, List<String> warnings) {
        Map<String, CompetencyAggregator> byKey = new LinkedHashMap<>();

        for (ExamResult result : examResults) {
            Map<String, Object> profile = result.getUserFeedbackJson();
            if (profile == null) {
                if (warnings != null) {
                    addWarningOnce(warnings, "feedback_profile_missing");
                }
                continue;
            }
            Object itemsRaw = profile.get("evaluationItems");
            if (!(itemsRaw instanceof List<?> items)) {
                if (warnings != null) {
                    addWarningOnce(warnings, "feedback_profile_invalid");
                }
                continue;
            }

            for (Object itemRaw : items) {
                if (!(itemRaw instanceof Map<?, ?> itemMap)) {
                    continue;
                }
                String key = extractCompetencyKey(itemMap);
                String label = extractCompetencyLabel(itemMap);
                Double score = extractItemScore(itemMap);
                String feedback = asString(itemMap.get("feedback"));
                if (score == null) {
                    continue;
                }

                CompetencyAggregator agg = byKey.computeIfAbsent(key, k -> new CompetencyAggregator(k, label));
                agg.add(score, feedback, resultTimestamp(result));
            }
        }

        if (byKey.isEmpty()) {
            return List.of();
        }

        return byKey.values().stream()
                .map(CompetencyAggregator::toDto)
                .sorted(Comparator.comparingDouble(
                        (CompetencyDto c) -> c.getAverageScorePercent() == null ? 0.0 : c.getAverageScorePercent()
                ).reversed())
                .toList();
    }

    private String extractCompetencyKey(Map<?, ?> itemMap) {
        Object detailsRaw = itemMap.get("evaluationDetails");
        if (detailsRaw instanceof Map<?, ?> details) {
            String key = firstNonBlank(
                    asString(details.get("competencyKey")),
                    asString(details.get("competency")),
                    asString(details.get("category")),
                    asString(details.get("skill"))
            );
            if (key != null) {
                return key;
            }
        }
        return DEFAULT_COMPETENCY_KEY;
    }

    private String extractCompetencyLabel(Map<?, ?> itemMap) {
        Object detailsRaw = itemMap.get("evaluationDetails");
        if (detailsRaw instanceof Map<?, ?> details) {
            String label = firstNonBlank(
                    asString(details.get("competencyLabel")),
                    asString(details.get("competencyName")),
                    asString(details.get("competency")),
                    asString(details.get("category")),
                    asString(details.get("skill"))
            );
            if (label != null) {
                return label;
            }
        }
        return DEFAULT_COMPETENCY_LABEL;
    }

    private Double extractItemScore(Map<?, ?> itemMap) {
        Object scoreRaw = itemMap.get("score");
        if (scoreRaw instanceof Number num) {
            return num.doubleValue();
        }
        if (scoreRaw instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static void addWarningOnce(List<String> warnings, String warning) {
        if (!warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Boolean asBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s);
        }
        return null;
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : raw) {
            String text = asString(item);
            if (text != null && !text.isBlank()) {
                result.add(text);
            }
        }
        return List.copyOf(result);
    }

    private static List<Object> asObjectList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return List.copyOf(raw);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static class CompetencyAggregator {
        private final String key;
        private final String label;
        private double sum = 0.0;
        private int count = 0;
        private String latestFeedback;
        private LocalDateTime latestTimestamp;

        CompetencyAggregator(String key, String label) {
            this.key = key;
            this.label = label;
        }

        void add(double score, String feedback, LocalDateTime when) {
            this.sum += score;
            this.count += 1;
            if (feedback != null && !feedback.isBlank()) {
                if (latestTimestamp == null || (when != null && when.isAfter(latestTimestamp))) {
                    this.latestFeedback = feedback;
                    this.latestTimestamp = when;
                }
            }
        }

        CompetencyDto toDto() {
            double avg = count == 0 ? 0.0 : sum / count;
            CompetencyStatus status;
            if (count < 1) {
                status = CompetencyStatus.INSUFFICIENT_DATA;
            } else if (avg >= STRONG_THRESHOLD) {
                status = CompetencyStatus.STRONG;
            } else if (avg >= WATCH_THRESHOLD) {
                status = CompetencyStatus.WATCH;
            } else {
                status = CompetencyStatus.NEEDS_IMPROVEMENT;
            }

            return CompetencyDto.builder()
                    .key(key)
                    .label(label)
                    .averageScorePercent(BigDecimal.valueOf(avg).setScale(1, RoundingMode.HALF_UP).doubleValue())
                    .evidenceCount(count)
                    .latestFeedback(latestFeedback)
                    .status(status.value())
                    .build();
        }
    }

    // ===========================================================
    // 활동/제출 집계
    // ===========================================================

    private ActivitySummaryDto computeActivitySummary(List<ExamResult> examResults, List<Submission> submissions) {
        return ActivitySummaryDto.builder()
                .examAttemptCount(examResults.size())
                .submissionCount(submissions.size())
                .latestActivityAt(computeLatestActivity(examResults, submissions))
                .build();
    }

    private LocalDateTime computeLatestActivity(List<ExamResult> examResults, List<Submission> submissions) {
        LocalDateTime latest = null;
        for (ExamResult er : examResults) {
            LocalDateTime t = resultTimestamp(er);
            if (t != null && (latest == null || t.isAfter(latest))) {
                latest = t;
            }
        }
        for (Submission s : submissions) {
            LocalDateTime t = s.getCreatedAt();
            if (t != null && (latest == null || t.isAfter(latest))) {
                latest = t;
            }
        }
        return latest;
    }

    private SubmissionSummaryDto computeSubmissionSummary(Long courseId, List<Submission> submissions) {
        long submitted = submissions.size();
        long graded = submissions.stream()
                .filter(s -> s.getExamResult() != null || s.getStatus() == SubmissionStatus.GRADED)
                .count();
        long pending = submitted - graded;
        long courseAssessmentCount = assessmentRepository.countByCourse_Id(courseId);
        long missing = Math.max(0L, courseAssessmentCount - submitted);

        return SubmissionSummaryDto.builder()
                .submittedCount((int) submitted)
                .gradedCount((int) graded)
                .pendingCount((int) pending)
                .missingCount((int) missing)
                .build();
    }

    // ===========================================================
    // 근거 (최신순 10건)
    // ===========================================================

    private List<EvidenceDto> buildEvidence(List<ExamResult> examResults, List<Submission> submissions) {
        List<EvidenceDto> all = new ArrayList<>();

        for (ExamResult er : examResults) {
            Double percent = toScorePercent(er);
            String examType = er.getExamSession() != null && er.getExamSession().getExamType() != null
                    ? er.getExamSession().getExamType().name()
                    : null;
            String lectureTitle = er.getExamSession() != null && er.getExamSession().getLecture() != null
                    ? er.getExamSession().getLecture().getTitle()
                    : null;
            all.add(EvidenceDto.builder()
                    .type("exam")
                    .examResultId(er.getId())
                    .examType(examType)
                    .lectureTitle(lectureTitle)
                    .completedAt(resultTimestamp(er))
                    .scorePercent(percent == null ? null : round1(percent))
                    .feedback(er.getOverallFeedback())
                    .build());
        }

        for (Submission s : submissions) {
            all.add(EvidenceDto.builder()
                    .type("submission")
                    .submissionId(s.getId())
                    .assessmentTitle(s.getAssessment() != null ? s.getAssessment().getTitle() : null)
                    .submittedAt(s.getCreatedAt())
                    .status(s.getStatus() != null ? s.getStatus().name() : null)
                    .build());
        }

        return all.stream()
                .sorted(Comparator.comparing(
                        (EvidenceDto e) -> e.getCompletedAt() != null ? e.getCompletedAt() : e.getSubmittedAt(),
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .limit(MAX_EVIDENCE)
                .toList();
    }

    // ===========================================================
    // 상태 / 서술 리포트
    // ===========================================================

    private ReportStatus computeReportStatus(int examAttemptCount,
                                             ScoreSummaryDto scoreSummary,
                                             List<CompetencyDto> competencies) {
        if (examAttemptCount < 1) {
            return ReportStatus.INSUFFICIENT_DATA;
        }
        Double avg = scoreSummary.getAverageScorePercent();
        if (avg == null) {
            return ReportStatus.INSUFFICIENT_DATA;
        }

        boolean hasNeedsImprovement = competencies.stream()
                .anyMatch(c -> CompetencyStatus.NEEDS_IMPROVEMENT.value().equals(c.getStatus()));
        if (hasNeedsImprovement) {
            return ReportStatus.NEEDS_ATTENTION;
        }
        if (avg != null && avg < NEEDS_ATTENTION_AVG_THRESHOLD) {
            return ReportStatus.NEEDS_ATTENTION;
        }

        boolean allCompetenciesStrong = !competencies.isEmpty() && competencies.stream()
                .allMatch(c -> c.getAverageScorePercent() != null
                        && c.getAverageScorePercent() >= EXCELLING_COMPETENCY_THRESHOLD);
        if (avg != null && avg >= EXCELLING_AVG_THRESHOLD && allCompetenciesStrong) {
            return ReportStatus.EXCELLING;
        }

        return ReportStatus.ON_TRACK;
    }

    private NarrativeReportDto buildNarrative(ScoreSummaryDto scoreSummary,
                                              List<CompetencyDto> competencies,
                                              ReportStatus reportStatus) {
        Double avg = scoreSummary.getAverageScorePercent();
        String summary;
        if (reportStatus == ReportStatus.INSUFFICIENT_DATA) {
            summary = "아직 응시한 시험이 없어 역량 분석을 제공할 수 없습니다.";
        } else if (reportStatus == ReportStatus.EXCELLING) {
            summary = String.format("평균 %.1f점으로 모든 역량에서 우수한 수행을 보이고 있습니다.", avg == null ? 0.0 : avg);
        } else if (reportStatus == ReportStatus.NEEDS_ATTENTION) {
            summary = String.format("평균 %.1f점, 일부 역량에서 보강이 필요합니다.", avg == null ? 0.0 : avg);
        } else {
            summary = String.format("평균 %.1f점, 전반적으로 안정적인 학습 흐름을 유지하고 있습니다.", avg == null ? 0.0 : avg);
        }

        List<String> strengths = competencies.stream()
                .filter(c -> CompetencyStatus.STRONG.value().equals(c.getStatus()))
                .sorted(Comparator.comparingDouble(
                        (CompetencyDto c) -> c.getAverageScorePercent() == null ? 0.0 : c.getAverageScorePercent()
                ).reversed())
                .limit(NARRATIVE_LIST_SIZE)
                .map(c -> String.format("%s 평균 %.1f점", c.getLabel(),
                        c.getAverageScorePercent() == null ? 0.0 : c.getAverageScorePercent()))
                .toList();

        List<String> improvements = competencies.stream()
                .filter(c -> CompetencyStatus.NEEDS_IMPROVEMENT.value().equals(c.getStatus()))
                .sorted(Comparator.comparingDouble(
                        c -> c.getAverageScorePercent() == null ? 0.0 : c.getAverageScorePercent()
                ))
                .limit(NARRATIVE_LIST_SIZE)
                .map(c -> String.format("%s 평균 %.1f점 — 보강 필요", c.getLabel(),
                        c.getAverageScorePercent() == null ? 0.0 : c.getAverageScorePercent()))
                .toList();

        List<String> nextSteps = new ArrayList<>();
        if (improvements.isEmpty()) {
            nextSteps.add("심화 문제로 강점 역량을 확장해 보세요.");
            nextSteps.add("새로운 시험 유형(서술형/토론형)에 도전해 응용력을 점검해 보세요.");
        } else {
            CompetencyDto weakest = competencies.stream()
                    .filter(c -> !CompetencyStatus.INSUFFICIENT_DATA.value().equals(c.getStatus()))
                    .min(Comparator.comparingDouble(
                            c -> c.getAverageScorePercent() == null ? 0.0 : c.getAverageScorePercent()
                    ))
                    .orElse(null);
            if (weakest != null) {
                nextSteps.add(String.format("%s 영역의 기본 개념을 다시 확인하고 유사 문제를 추가 풀이해 보세요.", weakest.getLabel()));
            }
            nextSteps.add("강의별 핵심 개념 요약을 복습하고 짧은 OX/플래시카드로 빠르게 점검하세요.");
        }
        if (nextSteps.size() < NARRATIVE_LIST_SIZE) {
            nextSteps.add("선생님과 1:1 피드백 세션을 통해 학습 방향을 점검해 보세요.");
        }

        return NarrativeReportDto.builder()
                .summary(summary)
                .strengths(strengths)
                .improvements(improvements)
                .nextSteps(nextSteps.subList(0, Math.min(nextSteps.size(), NARRATIVE_LIST_SIZE)))
                .build();
    }

    private String buildHeadline(ScoreSummaryDto scoreSummary, ReportStatus reportStatus) {
        Double avg = scoreSummary.getAverageScorePercent();
        if (reportStatus == ReportStatus.INSUFFICIENT_DATA || avg == null) {
            return "분석 가능한 학습 데이터가 더 필요합니다.";
        }
        if (reportStatus == ReportStatus.EXCELLING) {
            return String.format("평균 %.1f점으로 우수한 학습 흐름을 유지하고 있습니다.", avg == null ? 0.0 : avg);
        }
        if (reportStatus == ReportStatus.NEEDS_ATTENTION) {
            return String.format("평균 %.1f점으로 보완이 필요한 구간이 확인됩니다.", avg == null ? 0.0 : avg);
        }
        return String.format("평균 %.1f점으로 안정적인 학습 흐름을 보입니다.", avg == null ? 0.0 : avg);
    }

    private List<String> buildSummaryBullets(ActivitySummaryDto activitySummary,
                                             ScoreSummaryDto scoreSummary,
                                             SubmissionSummaryDto submissionSummary,
                                             ReportStatus reportStatus) {
        List<String> bullets = new ArrayList<>();
        Double avg = scoreSummary.getAverageScorePercent();
        if (avg != null) {
            bullets.add(String.format("종합 점수 %.1f점", avg));
        }
        bullets.add(String.format("시험 응시 %d회, 제출 %d건",
                activitySummary.getExamAttemptCount(), activitySummary.getSubmissionCount()));
        if (submissionSummary.getMissingCount() > 0) {
            bullets.add(String.format("미제출 %d건 확인", submissionSummary.getMissingCount()));
        }
        bullets.add("리포트 상태: " + reportStatus.value());
        return bullets;
    }

    private List<String> buildCoachingInsights(List<CompetencyDto> competencies,
                                               SubmissionSummaryDto submissionSummary,
                                               ReportStatus reportStatus) {
        List<String> insights = new ArrayList<>();
        competencies.stream()
                .filter(c -> CompetencyStatus.NEEDS_IMPROVEMENT.value().equals(c.getStatus()))
                .findFirst()
                .ifPresent(c -> insights.add(c.getLabel() + " 영역을 우선 점검하세요."));
        competencies.stream()
                .filter(c -> CompetencyStatus.STRONG.value().equals(c.getStatus()))
                .findFirst()
                .ifPresent(c -> insights.add(c.getLabel() + " 강점을 심화 문제로 확장할 수 있습니다."));
        if (submissionSummary.getMissingCount() > 0) {
            insights.add("미제출 과제를 먼저 정리하면 리포트 신뢰도가 올라갑니다.");
        }
        if (insights.isEmpty()) {
            insights.add(reportStatus == ReportStatus.INSUFFICIENT_DATA
                    ? "응시와 제출 데이터를 확보한 뒤 세부 코칭을 제공할 수 있습니다."
                    : "현재 흐름을 유지하면서 최근 오답 근거를 함께 확인하세요.");
        }
        return insights.stream().limit(3).toList();
    }

    // ===========================================================
    // 정렬 / 검색 / 필터
    // ===========================================================

    private void applyFilters(List<StudentReportListItem> items, String q, String statusFilter) {
        ReportStatus status = ReportStatus.fromQuery(statusFilter);
        if (q == null && status == null) {
            return;
        }
        String needle = q == null ? null : q.trim().toLowerCase();
        items.removeIf(item -> {
            if (needle != null && !needle.isEmpty()) {
                String name = item.getStudentName();
                if (name == null || !name.toLowerCase().contains(needle)) {
                    return true;
                }
            }
            if (status != null && !status.value().equals(item.getReportStatus())) {
                return true;
            }
            return false;
        });
    }

    private Comparator<StudentReportListItem> buildItemComparator(Sort sort) {
        Map<String, Integer> statusOrder = new HashMap<>();
        statusOrder.put(ReportStatus.NEEDS_ATTENTION.value(), 0);
        statusOrder.put(ReportStatus.INSUFFICIENT_DATA.value(), 1);
        statusOrder.put(ReportStatus.ON_TRACK.value(), 2);
        statusOrder.put(ReportStatus.EXCELLING.value(), 3);

        Comparator<StudentReportListItem> result = null;
        for (Sort.Order order : sort) {
            Comparator<StudentReportListItem> next = switch (order.getProperty()) {
                case "averageScore" -> Comparator.comparing(StudentReportListItem::getAverageScorePercent,
                        Comparator.nullsLast(Comparator.naturalOrder()));
                case "latestActivity" -> Comparator.comparing(StudentReportListItem::getLatestActivityAt,
                        Comparator.nullsLast(Comparator.naturalOrder()));
                case "reportStatus" -> Comparator.comparingInt(item ->
                        statusOrder.getOrDefault(item.getReportStatus(), Integer.MAX_VALUE));
                case "name" -> Comparator.comparing(StudentReportListItem::getStudentName,
                        Comparator.nullsLast(Comparator.naturalOrder()));
                default -> throw new BusinessException(CommonErrorCode.INVALID_PARAMETER,
                        "허용되지 않는 정렬 필드입니다: " + order.getProperty());
            };
            if (order.isDescending()) {
                next = next.reversed();
            }
            result = result == null ? next : result.thenComparing(next);
        }
        return result != null ? result : Comparator.comparing(StudentReportListItem::getStudentName,
                Comparator.nullsLast(Comparator.naturalOrder()));
    }

    // ===========================================================
    // util
    // ===========================================================

    private double round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private double round3(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }
}
