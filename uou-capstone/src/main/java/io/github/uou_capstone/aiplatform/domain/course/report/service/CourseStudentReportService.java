package io.github.uou_capstone.aiplatform.domain.course.report.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.assessment.repository.AssessmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.entity.Enrollment;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ActivitySummaryDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.CompetencyDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.CompetencyStatus;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.CourseInfoDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.CourseStudentReportDetailResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.CourseStudentReportListResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.EvidenceDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.NarrativeReportDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ReportStatus;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ScoreSummaryDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.StudentInfoDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.StudentReportCardDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.SubmissionSummaryDto;
import io.github.uou_capstone.aiplatform.domain.course.repository.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamResult;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamResultRepository;
import io.github.uou_capstone.aiplatform.domain.submission.entity.Submission;
import io.github.uou_capstone.aiplatform.domain.submission.entity.SubmissionStatus;
import io.github.uou_capstone.aiplatform.domain.submission.repository.SubmissionRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private static final double STRONG_THRESHOLD = 85.0;
    private static final double WATCH_THRESHOLD = 70.0;
    private static final double EXCELLING_AVG_THRESHOLD = 90.0;
    private static final double EXCELLING_COMPETENCY_THRESHOLD = 80.0;
    private static final double NEEDS_ATTENTION_AVG_THRESHOLD = 70.0;

    private static final String DEFAULT_COMPETENCY_KEY = "default";
    private static final String DEFAULT_COMPETENCY_LABEL = "문항 수행";

    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final ExamResultRepository examResultRepository;
    private final SubmissionRepository submissionRepository;
    private final AssessmentRepository assessmentRepository;
    private final CurrentUserResolver currentUserResolver;

    @Transactional(readOnly = true)
    public CourseStudentReportListResponse getStudentReportList(Long courseId,
                                                                String q,
                                                                String sortBy,
                                                                String direction,
                                                                String statusFilter) {
        Course course = loadCourseAsOwner(courseId);

        List<Enrollment> enrollments = enrollmentRepository.findByCourseIdWithStudentUser(courseId);
        if (enrollments.isEmpty()) {
            return CourseStudentReportListResponse.builder()
                    .courseId(course.getId())
                    .courseTitle(course.getTitle())
                    .totalStudents(0)
                    .students(List.of())
                    .build();
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

        List<StudentReportCardDto> cards = enrollments.stream()
                .map(enrollment -> buildCard(
                        enrollment,
                        examResultsByUserId.getOrDefault(enrollment.getStudent().getUser().getId(), List.of()),
                        submissionsByStudentId.getOrDefault(enrollment.getStudent().getId(), List.of())
                ))
                .collect(Collectors.toCollection(ArrayList::new));

        applyFilters(cards, q, statusFilter);
        applySort(cards, sortBy, direction);

        return CourseStudentReportListResponse.builder()
                .courseId(course.getId())
                .courseTitle(course.getTitle())
                .totalStudents(cards.size())
                .students(cards)
                .build();
    }

    @Transactional(readOnly = true)
    public CourseStudentReportDetailResponse getStudentReportDetail(Long courseId, Long studentId) {
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
        ReportStatus reportStatus = computeReportStatus(examResults.size(), scoreSummary, competencies);
        NarrativeReportDto narrative = buildNarrative(scoreSummary, competencies, reportStatus);

        return CourseStudentReportDetailResponse.builder()
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
                .narrativeReport(narrative)
                .reportStatus(reportStatus.value())
                .reportWarnings(warnings)
                .build();
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

    private StudentReportCardDto buildCard(Enrollment enrollment,
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

        return StudentReportCardDto.builder()
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

    private static String asString(Object value) {
        return value == null ? null : value.toString();
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

    // ===========================================================
    // 정렬 / 검색 / 필터
    // ===========================================================

    private void applyFilters(List<StudentReportCardDto> cards, String q, String statusFilter) {
        ReportStatus status = ReportStatus.fromQuery(statusFilter);
        if (q == null && status == null) {
            return;
        }
        String needle = q == null ? null : q.trim().toLowerCase();
        cards.removeIf(card -> {
            if (needle != null && !needle.isEmpty()) {
                String name = card.getStudentName();
                if (name == null || !name.toLowerCase().contains(needle)) {
                    return true;
                }
            }
            if (status != null && !status.value().equals(card.getReportStatus())) {
                return true;
            }
            return false;
        });
    }

    private void applySort(List<StudentReportCardDto> cards, String sortBy, String direction) {
        String sortKey = sortBy == null ? "name" : sortBy.trim();
        boolean asc = !"desc".equalsIgnoreCase(direction);

        Map<String, Integer> statusOrder = new HashMap<>();
        statusOrder.put(ReportStatus.NEEDS_ATTENTION.value(), 0);
        statusOrder.put(ReportStatus.INSUFFICIENT_DATA.value(), 1);
        statusOrder.put(ReportStatus.ON_TRACK.value(), 2);
        statusOrder.put(ReportStatus.EXCELLING.value(), 3);

        Comparator<StudentReportCardDto> cmp = switch (sortKey) {
            case "averageScore" -> Comparator.comparing(StudentReportCardDto::getAverageScorePercent,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "latestActivity" -> Comparator.comparing(StudentReportCardDto::getLatestActivityAt,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "reportStatus" -> Comparator.comparingInt(card ->
                    statusOrder.getOrDefault(card.getReportStatus(), Integer.MAX_VALUE));
            default -> Comparator.comparing(StudentReportCardDto::getStudentName,
                    Comparator.nullsLast(Comparator.naturalOrder()));
        };

        if (!asc) {
            cmp = cmp.reversed();
        }
        cards.sort(cmp);
    }

    // ===========================================================
    // util
    // ===========================================================

    private double round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
