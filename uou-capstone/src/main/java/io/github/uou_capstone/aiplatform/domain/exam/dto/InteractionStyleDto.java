package io.github.uou_capstone.aiplatform.domain.exam.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 상호작용 스타일
 */
@Getter
@Setter
public class InteractionStyleDto {
    private String languagePreference; // "ko", "en" 등
    private Boolean scenarioBased; // 시나리오 기반 문제 선호 여부
}
