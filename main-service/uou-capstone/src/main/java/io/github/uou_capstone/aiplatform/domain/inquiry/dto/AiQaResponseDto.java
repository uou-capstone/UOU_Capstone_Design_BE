package io.github.uou_capstone.aiplatform.domain.inquiry.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class AiQaResponseDto {
    @JsonProperty("supplementary_explanation") // ai-service의 응답 필드명에 맞춤
    private String supplementaryExplanation;
}