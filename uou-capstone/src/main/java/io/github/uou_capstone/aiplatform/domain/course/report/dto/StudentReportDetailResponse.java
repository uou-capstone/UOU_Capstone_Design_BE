package io.github.uou_capstone.aiplatform.domain.course.report.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

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
    private final NarrativeReportDto narrativeReport;
    private final String reportStatus;
    private final List<String> reportWarnings;
}
