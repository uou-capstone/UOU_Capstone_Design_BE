package io.github.uou_capstone.aiplatform.domain.course.report.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScoreSummaryDto {
    private final Double averageScorePercent;
    private final Double highestScorePercent;
    private final Double lowestScorePercent;
    /** 최근 3회(최신순) 점수 추이. 회차 부족 시 더 짧은 리스트. */
    private final List<Double> recentTrendPercent;
}
