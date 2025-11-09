package io.github.uou_capstone.aiplatform.domain.assessment.dto;

import io.github.uou_capstone.aiplatform.domain.assessment.AssessmentType;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
public class AssessmentCreateRequestDto {
    private String title;
    private AssessmentType type;
    private LocalDateTime dueDate;
    private List<QuestionCreateDto> questions;
}