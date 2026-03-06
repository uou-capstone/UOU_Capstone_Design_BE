package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * 프로젝트 메타데이터
 */
@Getter
@Setter
public class ProjectMetaDto {
    private String title;
    private String description;
    
    @JsonProperty("target_audience")
    private String targetAudience;
    
    @JsonProperty("estimated_length")
    private String estimatedLength;
    
    @JsonProperty("goal")
    private String goal;  // FastAPI 응답에 포함됨
    
    @JsonProperty("additional_info")
    private Map<String, Object> additionalInfo;
}
