package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 강의 자료 생성 Phase 1 요청 DTO
 * 키워드 기반 초기 분석 요청
 */
@Getter
@Setter
public class MaterialGenerationPhase1RequestDto {
    @NotNull(message = "강의 ID는 필수입니다.")
    private Long lectureId;
    
    @NotBlank(message = "키워드는 필수입니다.")
    private String keyword; // 초기 키워드 입력
}
