package io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto;

import io.github.uou_capstone.aiplatform.domain.course.report.criteria.entity.CourseReportCriterion;
import lombok.Getter;

@Getter
public class CriterionResponse {

    private final Long id;
    private final String label;
    private final String description;
    private final int weight;

    public CriterionResponse(CourseReportCriterion c) {
        this.id = c.getId();
        this.label = c.getLabel();
        this.description = c.getDescription();
        this.weight = c.getWeight();
    }
}
