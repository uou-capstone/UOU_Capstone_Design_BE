package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * FinalizedBrief DTO
 * Phase 2 산출물: 사용자 승인 완료된 기획안
 * DraftPlan과 동일한 스키마
 */
@Getter
@Setter
public class FinalizedBriefDto {
    @JsonProperty("project_meta")
    private ProjectMetaDto projectMeta;
    
    @JsonProperty("style_guide")
    private StyleGuideDto styleGuide;
    
    private List<ChapterDto> chapters;
}
