package io.github.uou_capstone.aiplatform.domain.exam.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 지식 범위. FastAPI(ko)는 PascalCase("Lecture_Material_Only")로 보내므로 수용.
 */
public enum ScopeBoundary {
    LECTURE_MATERIAL_ONLY("Lecture_Material_Only"),
    ALLOW_EXTERNAL_KNOWLEDGE("Allow_External_Knowledge");

    private final String apiValue;

    ScopeBoundary(String apiValue) {
        this.apiValue = apiValue;
    }

    @JsonValue
    public String getApiValue() {
        return apiValue;
    }

    @JsonCreator
    public static ScopeBoundary fromString(String value) {
        if (value == null) return null;
        for (ScopeBoundary e : values()) {
            if (e.apiValue.equals(value) || e.name().equals(value)) return e;
        }
        throw new IllegalArgumentException("Unknown ScopeBoundary: " + value);
    }
}
