package io.github.uou_capstone.aiplatform.domain.inquiry.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class InquiryRequestDto {
    @NotBlank(message = "AI 질문 ID는 필수입니다.")
    private String aiQuestionId;
    
    @NotBlank(message = "답변 내용은 필수입니다.")
    private String answerText;
}
