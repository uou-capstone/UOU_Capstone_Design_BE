package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * DraftPlan DTO
 * Phase 1 산출물: 프로젝트 기획안
 */
@Getter
@Setter
public class DraftPlanDto {
    @JsonProperty("project_meta")
    private ProjectMetaDto projectMeta;
    
    @JsonProperty("style_guide")
    private StyleGuideDto styleGuide;
    
    private List<ChapterDto> chapters;
}
