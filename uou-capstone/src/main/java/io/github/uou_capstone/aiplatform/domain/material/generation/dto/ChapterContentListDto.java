package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * ChapterContentList DTO
 * Phase 3 산출물: 모든 챕터의 콘텐츠 리스트
 */
@Getter
@Setter
public class ChapterContentListDto {
    private Long sessionId;
    private List<ChapterContentDto> chapters;
    private Integer totalChapters;
    private Integer progressPercentage;
}
