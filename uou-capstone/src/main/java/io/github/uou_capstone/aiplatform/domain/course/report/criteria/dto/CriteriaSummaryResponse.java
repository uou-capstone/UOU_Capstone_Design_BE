package io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class CriteriaSummaryResponse {

    private final int baseItemCount;
    private final int additionalItemCount;
    private final int activeCriteriaCount;
    private final String criteriaStatus;
    private final LocalDateTime criteriaReflectedAt;
}
