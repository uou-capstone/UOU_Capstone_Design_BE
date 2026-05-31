package io.github.uou_capstone.aiplatform.domain.course.report.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ClassroomLearningFlowResponse {

    private final Long courseId;
    private final List<FlowItem> items;

    @Getter
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FlowItem {
        private final Long lectureId;
        private final int week;
        private final String title;
        private final int materialCount;
        private final Double learningProgressPercent;
        private final Double averageScorePercent;
        private final long questionCount;
        private final long quizCount;
        private final Double participationRatePercent;
        private final String riskLevel;
        private final List<String> riskReasons;
    }
}
