package io.github.uou_capstone.aiplatform.domain.course.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class CourseCreateRequestDto { //과목생성

    @NotBlank(message = "과목명은 필수입니다.")
    private String title;
    
    @NotBlank(message = "과목 설명은 필수입니다.")
    private String description;
}
