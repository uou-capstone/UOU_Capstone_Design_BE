package io.github.uou_capstone.aiplatform.domain.course.report.dto.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiScoreSummaryDto {
    /** 0.0 ~ 1.0 비율. 입력이 없으면 null. */
    private final Double averageScoreRatio;
    /** 0.0 ~ 100.0 백분율 (소수 1자리). */
    private final Double averageScore;
    private final Double highestScore;
    private final Double lowestScore;
    /** 최근 N개 시험의 점수 비율 (0.0 ~ 1.0). */
    private final List<Double> recentTrend;
    private final AiScoreTrend trend;
}
