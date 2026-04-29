package io.github.uou_capstone.aiplatform.domain.course.report.dto;

public enum CompetencyStatus {
    STRONG("strong"),
    WATCH("watch"),
    NEEDS_IMPROVEMENT("needs_improvement"),
    INSUFFICIENT_DATA("insufficient_data");

    private final String value;

    CompetencyStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
