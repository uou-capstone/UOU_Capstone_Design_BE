package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 강의 자료 생성 Phase 1 응답 DTO
 */
@Getter
@Builder
public class MaterialGenerationPhase1ResponseDto {
    private Long sessionId;
    private DraftPlanDto draftPlan;
    private Integer progressPercentage;
    private String message;
}
