package io.github.uou_capstone.aiplatform.domain.course.report.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActivitySummaryDto {
    private final int examAttemptCount;
    private final int submissionCount;
    private final LocalDateTime latestActivityAt;
}
