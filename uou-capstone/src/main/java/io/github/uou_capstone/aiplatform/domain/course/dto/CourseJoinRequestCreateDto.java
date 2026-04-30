package io.github.uou_capstone.aiplatform.domain.course.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class CourseJoinRequestCreateDto {

    @NotBlank(message = "초대 코드는 필수입니다.")
    private String invitationCode;
}
