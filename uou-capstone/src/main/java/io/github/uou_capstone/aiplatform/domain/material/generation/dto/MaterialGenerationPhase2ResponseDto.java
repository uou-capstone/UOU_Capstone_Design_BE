package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 강의 자료 생성 Phase 2 응답 DTO
 */
@Getter
@Builder
public class MaterialGenerationPhase2ResponseDto {
    private Long sessionId;
    private DraftPlanDto draftPlan; // 수정된 DraftPlan (피드백 반영 시)
    private DraftPlanDto updatedPlan; // 피드백 반영 시 (하위 호환성)
    private FinalizedBriefDto finalizedBrief; // 승인 완료 시
    private Integer progressPercentage;
    private String message;
}
