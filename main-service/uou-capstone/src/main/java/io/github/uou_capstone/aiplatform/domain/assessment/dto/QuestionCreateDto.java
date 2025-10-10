package io.github.uou_capstone.aiplatform.domain.assessment.dto;


import io.github.uou_capstone.aiplatform.domain.assessment.CreatedBy;
import io.github.uou_capstone.aiplatform.domain.assessment.QuestionType;
import lombok.Getter;

import java.util.List;

@Getter
public class QuestionCreateDto {
    private String text;
    private QuestionType type;
    private CreatedBy createdBy;
    private List<ChoiceOptionCreateDto> choiceOptions;
}
