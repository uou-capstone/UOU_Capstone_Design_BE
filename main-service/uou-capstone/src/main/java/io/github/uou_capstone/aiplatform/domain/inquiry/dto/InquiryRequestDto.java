package io.github.uou_capstone.aiplatform.domain.inquiry.dto;

import lombok.Getter;

@Getter
public class InquiryRequestDto {
    private Long lectureId;
    private String questionText;
}