package io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CriterionCreateRequest {

    @NotBlank
    @Size(max = 100)
    private String label;

    @Size(max = 500)
    private String description;

    @Min(0)
    @Max(100)
    private int weight;
}
