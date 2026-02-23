package io.github.uou_capstone.aiplatform.domain.inquiry.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class AiQaResponseDto {
    //  ai-service의 handle_answer_question_stage 응답 필드
    @JsonProperty("supplementary")
    private String supplementary;
}