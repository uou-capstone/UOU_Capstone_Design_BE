package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import io.github.uou_capstone.aiplatform.domain.material.generation.GenerationPhase;
import lombok.Builder;
import lombok.Getter;

/**
 * 강의 자료 생성 상태 조회 DTO
 */
@Getter
@Builder
public class MaterialGenerationStatusDto {
    private Long sessionId;
    private GenerationPhase currentPhase;
    private Integer progressPercentage;
    private String finalDocument; // Phase 5 완료 시 Markdown 문서
    private String errorMessage; // 실패 시 에러 메시지
}
