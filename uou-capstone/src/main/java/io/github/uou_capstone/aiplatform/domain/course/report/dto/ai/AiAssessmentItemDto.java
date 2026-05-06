package io.github.uou_capstone.aiplatform.domain.course.report.dto.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiAssessmentItemDto {
    private final Long assessmentId;
    private final String title;
    private final boolean submitted;
    private final BigDecimal score;
    private final BigDecimal maxScore;
    /** 0.0 ~ 1.0 비율. score/maxScore 가 모두 있을 때만 채움. */
    private final Double scoreRatio;
    private final LocalDateTime submittedAt;
    private final String feedback;
    private final List<String> weakConcepts;
}
