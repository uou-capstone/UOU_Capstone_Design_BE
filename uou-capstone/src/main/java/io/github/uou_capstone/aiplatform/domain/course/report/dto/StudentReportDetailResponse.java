package io.github.uou_capstone.aiplatform.domain.course.report.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.AiIntegratedLearningSummaryDto;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.AiLearningEvidenceDto;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StudentReportDetailResponse {
    private final StudentInfoDto student;
    private final CourseInfoDto course;
    private final ActivitySummaryDto activitySummary;
    private final ScoreSummaryDto scoreSummary;
    private final List<CompetencyDto> competencies;
    private final SubmissionSummaryDto submissionSummary;
    private final List<EvidenceDto> evidence;
    private final AiIntegratedLearningSummaryDto integratedLearningSummary;
    private final List<AiLearningEvidenceDto> learningEvidence;
    private final NarrativeReportDto narrativeReport;
    private final Double overallScorePercent;
    private final String headline;
    private final List<String> summaryBullets;
    private final List<String> strengths;
    private final List<String> improvementPoints;
    private final List<String> coachingInsights;
    private final List<String> recommendedActions;
    private final LocalDateTime generatedAt;
    private final LocalDateTime updatedAt;
    private final String reportStatus;
    private final List<String> reportWarnings;
}
