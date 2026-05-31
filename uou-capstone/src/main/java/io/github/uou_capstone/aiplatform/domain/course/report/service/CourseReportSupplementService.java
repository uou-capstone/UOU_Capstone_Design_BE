package io.github.uou_capstone.aiplatform.domain.course.report.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.assessment.repository.AssessmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.discussion.entity.DiscussionCategory;
import io.github.uou_capstone.aiplatform.domain.course.discussion.repository.DiscussionRepository;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.entity.Enrollment;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.LectureRepository;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ClassroomFlowRiskLevel;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ClassroomLearningFlowResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.StudentReportActivitySummaryResponse;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseAccessService;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamResult;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamResultRepository;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamSessionRepository;
import io.github.uou_capstone.aiplatform.domain.inquiry.StudentInquiryRepository;
import io.github.uou_capstone.aiplatform.domain.learning.repository.LearningChatMessageRepository;
import io.github.uou_capstone.aiplatform.domain.learning.repository.LearningChatSessionRepository;
import io.github.uou_capstone.aiplatform.domain.material.entity.Material;
import io.github.uou_capstone.aiplatform.domain.material.repository.MaterialRepository;
import io.github.uou_capstone.aiplatform.domain.submission.entity.Submission;
import io.github.uou_capstone.aiplatform.domain.submission.repository.SubmissionRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CourseReportSupplementService {

    private static final double RISK_SCORE_THRESHOLD = 70.0;
    private static final double LOW_PARTICIPATION_THRESHOLD = 50.0;

    private final CourseAccessService courseAccessService;
    private final EnrollmentRepository enrollmentRepository;
    private final ExamResultRepository examResultRepository;
    private final SubmissionRepository submissionRepository;
    private final AssessmentRepository assessmentRepository;
    private final StudentInquiryRepository studentInquiryRepository;
    private final DiscussionRepository discussionRepository;
    private final LectureRepository lectureRepository;
    private final LearningChatSessionRepository learningChatSessionRepository;
    private final LearningChatMessageRepository learningChatMessageRepository;
    private final MaterialRepository materialRepository;
    private final ExamSessionRepository examSessionRepository;

    @Transactional(readOnly = true)
    public StudentReportActivitySummaryResponse getStudentActivitySummary(Long courseId, Long studentId) {
        courseAccessService.loadCourseAsTeacher(courseId);
        Enrollment enrollment = enrollmentRepository.findByCourseIdAndStudentIdWithUser(courseId, studentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        Student student = enrollment.getStudent();
        User studentUser = student.getUser();

        List<ExamResult> examResults = examResultRepository
                .findByCourseIdAndUserIdWithSession(courseId, studentUser.getId());
        List<Submission> submissions = submissionRepository
                .findByCourseIdAndStudentIdWithAssessment(courseId, student.getId());
        long totalAssessments = assessmentRepository.countByCourse_Id(courseId);

        long inquiryCount = studentInquiryRepository.countByLecture_Course_IdAndStudent_Id(courseId, studentId);
        long discussionQuestionCount = discussionRepository.countByCourse_IdAndAuthor_IdAndCategory(
                courseId, studentUser.getId(), DiscussionCategory.QUESTION);
        long totalLectures = lectureRepository.countByCourseId(courseId);
        long coveredLectures = learningChatSessionRepository
                .findDistinctLectureIdsByCourseAndUser(courseId, studentUser.getId())
                .size();

        return StudentReportActivitySummaryResponse.builder()
                .questionCount(inquiryCount + discussionQuestionCount)
                .examAttemptCount(examResults.size())
                .submissionCount(submissions.size())
                .missingSubmissionCount((int) Math.max(0L, totalAssessments - submissions.size()))
                .lectureProgressPercent(totalLectures == 0 ? null : round1(coveredLectures * 100.0 / totalLectures))
                .pageCoverage(buildPageCoverage(courseId, studentUser.getId()))
                .categoryCoverage(buildCategoryCoverage(courseId, studentUser.getId(), inquiryCount))
                .build();
    }

    @Transactional(readOnly = true)
    public ClassroomLearningFlowResponse getClassroomFlow(Long courseId) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        List<Lecture> lectures = lectureRepository.findByCourseIdOrderByWeekNumberAscIdAsc(courseId);
        if (lectures.isEmpty()) {
            return ClassroomLearningFlowResponse.builder()
                    .courseId(course.getId())
                    .items(List.of())
                    .build();
        }

        List<Long> lectureIds = lectures.stream().map(Lecture::getId).toList();
        Map<Long, Long> materialCounts = materialRepository.findByLecture_IdInOrderByLecture_IdAscCreatedAtDesc(lectureIds)
                .stream()
                .collect(Collectors.groupingBy(m -> m.getLecture().getId(), Collectors.counting()));
        Map<Long, Long> quizCounts = examSessionRepository.findByLecture_Course_Id(courseId).stream()
                .collect(Collectors.groupingBy(es -> es.getLecture().getId(), Collectors.counting()));
        Map<Long, List<ExamResult>> resultsByLecture = examResultRepository.findByCourseIdWithSession(courseId)
                .stream()
                .collect(Collectors.groupingBy(er -> er.getExamSession().getLecture().getId()));
        Map<Long, Long> inquiryCounts = toLongCountMap(studentInquiryRepository.countByLectureForCourse(courseId));

        List<Enrollment> enrollments = enrollmentRepository.findByCourseIdWithStudentUser(courseId);
        int studentCount = enrollments.size();
        Map<Long, Long> studentIdByUserId = enrollments.stream()
                .collect(Collectors.toMap(
                        e -> e.getStudent().getUser().getId(),
                        e -> e.getStudent().getId(),
                        (a, b) -> a));

        Map<Long, Set<Long>> learningParticipants = toLectureStudentSets(
                learningChatSessionRepository.findLectureUserPairsByCourse(courseId), studentIdByUserId, true);
        Map<Long, Set<Long>> inquiryParticipants = toLectureStudentSets(
                studentInquiryRepository.findLectureStudentPairsByCourse(courseId), studentIdByUserId, false);
        Map<Long, Set<Long>> resultParticipants = buildResultParticipants(resultsByLecture, studentIdByUserId);
        Map<Long, Set<Long>> submissionParticipants = buildSubmissionParticipants(courseId);

        List<ClassroomLearningFlowResponse.FlowItem> items = lectures.stream()
                .map(lecture -> buildFlowItem(
                        lecture,
                        materialCounts.getOrDefault(lecture.getId(), 0L).intValue(),
                        quizCounts.getOrDefault(lecture.getId(), 0L),
                        inquiryCounts.getOrDefault(lecture.getId(), 0L),
                        resultsByLecture.getOrDefault(lecture.getId(), List.of()),
                        mergeParticipants(lecture.getId(), learningParticipants, inquiryParticipants,
                                resultParticipants, submissionParticipants),
                        studentCount,
                        learningParticipants.getOrDefault(lecture.getId(), Set.of()).size()))
                .toList();

        return ClassroomLearningFlowResponse.builder()
                .courseId(course.getId())
                .items(items)
                .build();
    }

    private List<StudentReportActivitySummaryResponse.PageCoverageItem> buildPageCoverage(Long courseId, Long userId) {
        return learningChatMessageRepository.countPagesByCourseAndUser(courseId, userId).stream()
                .map(row -> StudentReportActivitySummaryResponse.PageCoverageItem.builder()
                        .pageNumber(((Number) row[0]).intValue())
                        .messageCount(((Number) row[1]).longValue())
                        .build())
                .toList();
    }

    private List<StudentReportActivitySummaryResponse.CategoryCoverageItem> buildCategoryCoverage(Long courseId,
                                                                                                  Long userId,
                                                                                                  long inquiryCount) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (inquiryCount > 0) {
            counts.put("INQUIRY", inquiryCount);
        }
        for (Object[] row : discussionRepository.countByCategoryForAuthor(courseId, userId)) {
            counts.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        return counts.entrySet().stream()
                .map(e -> StudentReportActivitySummaryResponse.CategoryCoverageItem.builder()
                        .category(e.getKey())
                        .count(e.getValue())
                        .build())
                .toList();
    }

    private ClassroomLearningFlowResponse.FlowItem buildFlowItem(Lecture lecture,
                                                                 int materialCount,
                                                                 long quizCount,
                                                                 long questionCount,
                                                                 List<ExamResult> results,
                                                                 Set<Long> participants,
                                                                 int studentCount,
                                                                 int learningParticipantCount) {
        Double averageScore = averageScore(results);
        Double participation = studentCount == 0 ? null : round1(participants.size() * 100.0 / studentCount);
        Double learningProgress = studentCount == 0 ? null : round1(learningParticipantCount * 100.0 / studentCount);
        List<String> riskReasons = new ArrayList<>();
        boolean hasActivity = !results.isEmpty() || questionCount > 0 || learningParticipantCount > 0;
        if (averageScore != null && averageScore < RISK_SCORE_THRESHOLD) {
            riskReasons.add("LOW_AVERAGE_SCORE");
        }
        if (participation != null && participation < LOW_PARTICIPATION_THRESHOLD) {
            riskReasons.add("LOW_PARTICIPATION");
        }
        if (!hasActivity) {
            riskReasons.add("NO_ACTIVITY");
        }
        ClassroomFlowRiskLevel riskLevel = resolveRiskLevel(studentCount, hasActivity, riskReasons);

        return ClassroomLearningFlowResponse.FlowItem.builder()
                .lectureId(lecture.getId())
                .week(lecture.getWeekNumber())
                .title(lecture.getTitle())
                .materialCount(materialCount)
                .learningProgressPercent(learningProgress)
                .averageScorePercent(averageScore)
                .questionCount(questionCount)
                .quizCount(quizCount)
                .participationRatePercent(participation)
                .riskLevel(riskLevel.name())
                .riskReasons(riskReasons)
                .build();
    }

    private ClassroomFlowRiskLevel resolveRiskLevel(int studentCount, boolean hasActivity, List<String> riskReasons) {
        if (studentCount == 0 || !hasActivity) {
            return ClassroomFlowRiskLevel.INSUFFICIENT_DATA;
        }
        if (riskReasons.contains("LOW_AVERAGE_SCORE") && riskReasons.contains("LOW_PARTICIPATION")) {
            return ClassroomFlowRiskLevel.HIGH;
        }
        if (!riskReasons.isEmpty()) {
            return ClassroomFlowRiskLevel.MEDIUM;
        }
        return ClassroomFlowRiskLevel.LOW;
    }

    private Map<Long, Long> toLongCountMap(List<Object[]> rows) {
        Map<Long, Long> result = new HashMap<>();
        for (Object[] row : rows) {
            result.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return result;
    }

    private Map<Long, Set<Long>> toLectureStudentSets(List<Object[]> rows,
                                                      Map<Long, Long> studentIdByUserId,
                                                      boolean secondColumnIsUserId) {
        Map<Long, Set<Long>> result = new HashMap<>();
        for (Object[] row : rows) {
            Long lectureId = ((Number) row[0]).longValue();
            Long rawId = ((Number) row[1]).longValue();
            Long studentId = secondColumnIsUserId ? studentIdByUserId.get(rawId) : rawId;
            if (studentId != null) {
                result.computeIfAbsent(lectureId, ignored -> new HashSet<>()).add(studentId);
            }
        }
        return result;
    }

    private Map<Long, Set<Long>> buildResultParticipants(Map<Long, List<ExamResult>> resultsByLecture,
                                                        Map<Long, Long> studentIdByUserId) {
        Map<Long, Set<Long>> result = new HashMap<>();
        for (Map.Entry<Long, List<ExamResult>> entry : resultsByLecture.entrySet()) {
            for (ExamResult examResult : entry.getValue()) {
                Long studentId = studentIdByUserId.get(examResult.getUser().getId());
                if (studentId != null) {
                    result.computeIfAbsent(entry.getKey(), ignored -> new HashSet<>()).add(studentId);
                }
            }
        }
        return result;
    }

    private Map<Long, Set<Long>> buildSubmissionParticipants(Long courseId) {
        Map<Long, Set<Long>> result = new HashMap<>();
        for (Submission submission : submissionRepository.findByCourseIdWithAssessmentAndStudent(courseId)) {
            ExamSession session = submission.getAssessment().getExamSession();
            if (session == null || session.getLecture() == null) {
                continue;
            }
            result.computeIfAbsent(session.getLecture().getId(), ignored -> new HashSet<>())
                    .add(submission.getStudent().getId());
        }
        return result;
    }

    @SafeVarargs
    private Set<Long> mergeParticipants(Long lectureId, Map<Long, Set<Long>>... sources) {
        Set<Long> result = new HashSet<>();
        for (Map<Long, Set<Long>> source : sources) {
            result.addAll(source.getOrDefault(lectureId, Set.of()));
        }
        return result;
    }

    private Double averageScore(List<ExamResult> results) {
        List<Double> percents = results.stream()
                .map(this::toScorePercent)
                .filter(Objects::nonNull)
                .toList();
        if (percents.isEmpty()) {
            return null;
        }
        return round1(percents.stream().mapToDouble(Double::doubleValue).average().orElse(0.0));
    }

    private Double toScorePercent(ExamResult result) {
        BigDecimal total = result.getTotalScore();
        BigDecimal max = result.getMaxScore();
        if (total == null || max == null || max.signum() == 0) {
            return null;
        }
        return total.doubleValue() / max.doubleValue() * 100.0;
    }

    private double round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
