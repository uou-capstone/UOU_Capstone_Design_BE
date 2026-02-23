package io.github.uou_capstone.aiplatform.domain.course.lecture.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class LectureCreateRequestDto {

    @NotBlank(message = "강의 제목은 필수 입력값입니다.")
    private String title;

    @Min(value = 0, message = "주차는 0주차 이상이어야 합니다.") // 0주차(OT) 허용
    private int weekNumber;

    private String description; // 필수 아님 (null 허용)
}
