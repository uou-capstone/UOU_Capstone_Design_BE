package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Phase 3-5 비동기 처리 요청 DTO
 */
@Data
public class MaterialGenerationAsyncRequestDto {
    @NotNull(message = "sessionId는 필수입니다.")
    private Long sessionId;
}
