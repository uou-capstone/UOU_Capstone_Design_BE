package io.github.uou_capstone.aiplatform.domain.course.lecture.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
public class LectureUpdateRequestDto {
    
    @NotBlank(message = "강의 제목은 필수입니다.")
    private String title;
    
    @NotNull(message = "주차 정보는 필수입니다.")
    @Min(value = 1, message = "1주차 이상이어야 합니다.")
    private Integer weekNumber; 
    
    @NotBlank(message = "강의 설명은 필수입니다.")
    private String description;
}
