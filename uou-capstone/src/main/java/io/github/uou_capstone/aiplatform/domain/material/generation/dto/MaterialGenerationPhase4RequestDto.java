package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 강의 자료 생성 Phase 4 요청 DTO
 * 검증 및 수정 요청
 */
@Getter
@Setter
public class MaterialGenerationPhase4RequestDto {
    @NotNull(message = "세션 ID는 필수입니다.")
    private Long sessionId;
}
