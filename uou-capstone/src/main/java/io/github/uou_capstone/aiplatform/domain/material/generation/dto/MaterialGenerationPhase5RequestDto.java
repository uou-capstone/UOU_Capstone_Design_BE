package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 강의 자료 생성 Phase 5 요청 DTO
 * 최종 조립 요청
 */
@Getter
@Setter
public class MaterialGenerationPhase5RequestDto {
    @NotNull(message = "세션 ID는 필수입니다.")
    private Long sessionId;
}
