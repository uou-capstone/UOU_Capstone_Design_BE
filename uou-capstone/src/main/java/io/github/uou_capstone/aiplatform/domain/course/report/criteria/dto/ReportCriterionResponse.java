package io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.entity.CourseReportCriterion;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReportCriterionResponse {

    private final String id;
    private final String key;
    private final Long criterionId;
    private final String label;
    private final String description;
    private final int weight;
    private final boolean builtIn;
    private final boolean editable;
    private final boolean deletable;
    private final List<String> dataSourceHint;
    private final String fallbackPolicy;

    public static ReportCriterionResponse custom(CourseReportCriterion c) {
        String key = "custom:" + c.getId();
        return ReportCriterionResponse.builder()
                .id(key)
                .key(key)
                .criterionId(c.getId())
                .label(c.getLabel())
                .description(c.getDescription())
                .weight(c.getWeight())
                .builtIn(false)
                .editable(true)
                .deletable(true)
                .fallbackPolicy("INSUFFICIENT_EVIDENCE")
                .build();
    }
}
