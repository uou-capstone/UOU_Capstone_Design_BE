package io.github.uou_capstone.aiplatform.domain.course.report.dto.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.time.LocalDateTime;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiIntegratedLearningSummaryDto {
    private final long quizAttemptCount;
    private final long passCount;
    private final long failCount;
    private final Double averageScoreRatio;
    private final List<String> weakConcepts;
    private final List<String> resolvedConcepts;
    private final LocalDateTime latestActivityAt;
}
