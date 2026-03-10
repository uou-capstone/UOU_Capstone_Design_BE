package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import io.github.uou_capstone.aiplatform.domain.material.generation.GenerationPhase;
import lombok.Builder;
import lombok.Getter;

/**
 * 창이 닫히거나 새로고침 후에도 진행 중인 생성 세션을 재개할 수 있도록
 * 강의(lectureId) 기준 최신 GenerationSession을 반환하는 DTO.
 */
@Getter
@Builder
public class MaterialGenerationResumeDto {
    private Long sessionId;
    private Long lectureId;
    private GenerationPhase currentPhase;
    private Integer progressPercentage;

    private DraftPlanDto draftPlan;
    private FinalizedBriefDto finalizedBrief;
    private ChapterContentListDto chapterContentList;
    private VerifiedContentDto verifiedContent;
    private String finalDocument;
    private String errorMessage;
}

