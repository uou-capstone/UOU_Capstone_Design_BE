package io.github.uou_capstone.aiplatform.domain.assessment.dto;

import io.github.uou_capstone.aiplatform.domain.assessment.ChoiceOption;
import lombok.Getter;

@Getter
public class OptionResponseDto { /// 평가, 문제, 선택지의 계층 구조를 그대로 담는 응답(Response) DTO
    private final Long optionId;
    private final String text;


    public OptionResponseDto(ChoiceOption option) {
        this.optionId = option.getId();
        this.text = option.getText();
    }
}