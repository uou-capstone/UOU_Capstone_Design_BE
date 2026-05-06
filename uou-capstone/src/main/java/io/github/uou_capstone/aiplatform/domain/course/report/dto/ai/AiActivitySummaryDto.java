package io.github.uou_capstone.aiplatform.domain.course.report.dto.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiActivitySummaryDto {
    private final long totalAssessments;
    private final int submittedCount;
    private final long missingCount;
    private final LocalDateTime latestSubmittedAt;
}
