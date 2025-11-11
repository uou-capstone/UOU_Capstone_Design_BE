package io.github.uou_capstone.aiplatform.domain.inquiry.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AiQaRequestDto {
    @JsonProperty("original_q") //  ai-service 모델의 필드명(스네이크 케이스)에 맞춤
    private String originalQ;

    @JsonProperty("user_answer")
    private String userAnswer;

    @JsonProperty("pdf_path")
    private String pdfPath;
}