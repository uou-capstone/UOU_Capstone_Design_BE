package io.github.uou_capstone.aiplatform.domain.course.report.dto.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiIntegratedLearningSummaryDto {
    private final long quizCount;
    private final Double averageScoreRatio;
    private final List<String> weakConcepts;
    private final long resolvedInterventions;
}
