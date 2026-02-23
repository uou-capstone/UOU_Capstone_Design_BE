package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

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
    private String targetAudience;
    private String estimatedLength;
    private Map<String, Object> additionalInfo;
}
