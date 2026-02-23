package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 강의 자료 생성 Phase 4 응답 DTO
 */
@Getter
@Builder
public class MaterialGenerationPhase4ResponseDto {
    private Long sessionId;
    private VerifiedContentDto verifiedContent;
    private Integer progressPercentage;
    private String message;
}
