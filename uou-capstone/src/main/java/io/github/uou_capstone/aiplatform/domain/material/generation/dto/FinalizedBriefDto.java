package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

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
    private ProjectMetaDto projectMeta;
    private StyleGuideDto styleGuide;
    private List<ChapterDto> chapters;
}
