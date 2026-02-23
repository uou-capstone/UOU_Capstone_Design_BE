package io.github.uou_capstone.aiplatform.domain.assessment.dto;

import io.github.uou_capstone.aiplatform.domain.assessment.entity.AssessmentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
public class AssessmentCreateRequestDto {
    @NotBlank(message = "평가 제목은 필수입니다.")
    private String title;
    
    @NotNull(message = "평가 유형은 필수입니다.")
    private AssessmentType type;
    
    private LocalDateTime dueDate;
    
    private List<QuestionCreateDto> questions;
}
