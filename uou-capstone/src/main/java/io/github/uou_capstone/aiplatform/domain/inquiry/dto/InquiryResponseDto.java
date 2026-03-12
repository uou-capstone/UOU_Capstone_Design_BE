package io.github.uou_capstone.aiplatform.domain.inquiry.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class InquiryResponseDto {
    private String status;
    private String explanation;
    private List<AiQaResponseDto.RemedialStep> steps;
}
