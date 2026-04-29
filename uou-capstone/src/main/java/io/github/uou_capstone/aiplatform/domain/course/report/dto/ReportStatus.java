package io.github.uou_capstone.aiplatform.domain.course.report.dto;

public enum ReportStatus {
    EXCELLING("excelling"),
    ON_TRACK("on_track"),
    NEEDS_ATTENTION("needs_attention"),
    INSUFFICIENT_DATA("insufficient_data");

    private final String value;

    ReportStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static ReportStatus fromQuery(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase();
        if (normalized.isEmpty() || "all".equals(normalized)) {
            return null;
        }
        for (ReportStatus status : values()) {
            if (status.value.equals(normalized)) {
                return status;
            }
        }
        return null;
    }
}
