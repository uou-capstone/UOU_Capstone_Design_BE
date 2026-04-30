package io.github.uou_capstone.aiplatform.domain.course.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CourseJoinRequestCreateDto {

    @NotBlank(message = "초대 코드는 필수입니다.")
    private String invitationCode;

    @JsonCreator
    public CourseJoinRequestCreateDto(@JsonProperty("invitationCode") String invitationCode) {
        this.invitationCode = invitationCode;
    }
}
