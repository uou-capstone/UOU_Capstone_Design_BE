package io.github.uou_capstone.aiplatform.domain.course.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class CourseUpdateRequestDto {
    @NotBlank(message = "과목명은 필수입니다.")
    @Size(max = 255, message = "과목명은 최대 255자까지 입력할 수 있습니다.")
    private String title;

    @NotBlank(message = "과목 설명은 필수입니다.")
    @Size(max = 20000, message = "과목 설명은 최대 20000자까지 입력할 수 있습니다.")
    private String description;
}
