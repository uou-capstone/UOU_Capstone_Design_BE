package io.github.uou_capstone.aiplatform.domain.inquiry.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AiQaRequestDto {
    @JsonProperty("lectureId") //  ai-service 페이로드에 맞춤
    private Long lectureId;

    @JsonProperty("aiQuestionId") //  ai-service 페이로드에 맞춤
    private String aiQuestionId;

    @JsonProperty("answer") //  ai-service 페이로드에 맞춤
    private String answer;
}