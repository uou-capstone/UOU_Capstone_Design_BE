package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * 스타일 가이드
 */
@Getter
@Setter
public class StyleGuideDto {
    private String tone; // "formal", "casual", "academic" 등
    private String language; // "ko", "en" 등
    private String complexity; // "beginner", "intermediate", "advanced"
    private List<String> keyPoints;
    private Map<String, Object> stylePreferences;
}
