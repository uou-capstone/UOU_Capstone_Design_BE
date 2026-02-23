package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 강의 자료 생성 Phase 3 요청 DTO
 * 콘텐츠 생성 요청
 */
@Getter
@Setter
public class MaterialGenerationPhase3RequestDto {
    @NotNull(message = "세션 ID는 필수입니다.")
    private Long sessionId;
}
