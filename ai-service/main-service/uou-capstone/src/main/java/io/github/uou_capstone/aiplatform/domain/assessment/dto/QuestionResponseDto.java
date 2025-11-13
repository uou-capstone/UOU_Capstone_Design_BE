package io.github.uou_capstone.aiplatform.domain.assessment.dto;

import io.github.uou_capstone.aiplatform.domain.assessment.ChoiceOption;
import io.github.uou_capstone.aiplatform.domain.assessment.Question;
import io.github.uou_capstone.aiplatform.domain.assessment.QuestionType;
import lombok.Getter;

import java.util.List;
import java.util.stream.Collectors;

@Getter
public class QuestionResponseDto { /// 문제 정보 + 선택지 목록
    private final Long questionId;
    private final String text;
    private final QuestionType type;
    private final List<OptionResponseDto> options;

    public QuestionResponseDto(Question question) {
        this.questionId = question.getId();
        this.text = question.getText();
        this.type = question.getType();

        // Question 엔티티에서 바로 options 리스트를 가져와 변환
        this.options = question.getOptions().stream()
                .map(OptionResponseDto::new)
                .collect(Collectors.toList());
    }
}