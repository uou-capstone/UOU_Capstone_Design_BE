package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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
    private String tone;
    
    @JsonProperty("detail_level")
    private String detailLevel;
    
    @JsonProperty("math_policy")
    private String mathPolicy;
    
    @JsonProperty("example_policy")
    private String examplePolicy;
    
    private String formatting;
    
    // 기존 필드들 (호환성 유지)
    private String language;
    private String complexity;
    
    @JsonProperty("key_points")
    private List<String> keyPoints;
    
    @JsonProperty("style_preferences")
    private Map<String, Object> stylePreferences;
}
