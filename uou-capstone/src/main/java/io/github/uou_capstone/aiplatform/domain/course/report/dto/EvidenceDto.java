package io.github.uou_capstone.aiplatform.domain.course.report.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 학생 리포트 근거 항목 (시험 결과 / 과제 제출 통합).
 * type: "exam" 또는 "submission".
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EvidenceDto {
    private final String type;
    private final Long examResultId;
    private final String examType;
    private final String lectureTitle;
    private final LocalDateTime completedAt;
    private final Double scorePercent;
    private final String feedback;

    private final Long submissionId;
    private final String assessmentTitle;
    private final LocalDateTime submittedAt;
    private final String status;
}
