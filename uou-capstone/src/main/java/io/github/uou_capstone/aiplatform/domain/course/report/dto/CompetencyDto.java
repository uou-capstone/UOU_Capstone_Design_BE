package io.github.uou_capstone.aiplatform.domain.course.report.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CompetencyDto {
    private final String key;
    private final String label;
    private final Double averageScorePercent;
    private final int evidenceCount;
    private final String latestFeedback;
    private final String status;
}
