package io.github.uou_capstone.aiplatform.domain.assessment.dto;


import io.github.uou_capstone.aiplatform.domain.assessment.entity.CreatedBy;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import lombok.Getter;

import java.util.List;

@Getter
public class QuestionCreateDto {
    private String text;
    private ExamType type;  // v2: ExamType 사용
    private CreatedBy createdBy;
    private List<ChoiceOptionCreateDto> choiceOptions;
}
