package io.github.uou_capstone.aiplatform.domain.course.report.dto.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * FastAPI {@code POST /api/v3/report/student/analyze} 가 입력으로 받는 학생 분석 컨텍스트.
 *
 * Spring 은 분석을 호출하지 않고 이 DTO 만 반환한다. FastAPI 가 받아서 자체 분석을 수행.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StudentAiReportContextResponse {
    private final AiCourseInfoDto course;
    private final AiStudentInfoDto student;
    private final AiActivitySummaryDto activitySummary;
    private final AiScoreSummaryDto scoreSummary;
    private final List<AiAssessmentItemDto> assessments;
    private final List<AiCompetencyDto> competencies;
    private final List<AiEvidenceItemDto> evidence;
    private final List<AiLearningEvidenceDto> learningEvidence;
    private final AiIntegratedLearningSummaryDto integratedLearningSummary;
    private final AiNarrativeDto existingNarrative;
    private final List<String> reportWarnings;
}
