package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * VerifiedContent DTO
 * Phase 4 산출물: 검증 및 수정 완료된 콘텐츠
 */
@Getter
@Setter
public class VerifiedContentDto {
    private Long sessionId;
    private List<ChapterContentDto> chapters;
    private List<String> qualityChecks; // 품질 검증 항목
    private Map<String, Object> verificationMetadata;
    private Integer progressPercentage;
}
