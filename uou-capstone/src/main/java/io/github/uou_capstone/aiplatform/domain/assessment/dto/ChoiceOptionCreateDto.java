package io.github.uou_capstone.aiplatform.domain.assessment.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Getter;

@Getter
public class ChoiceOptionCreateDto {

    private String text;

    @JsonAlias({"is_correct"})
    private boolean isCorrect;
}
