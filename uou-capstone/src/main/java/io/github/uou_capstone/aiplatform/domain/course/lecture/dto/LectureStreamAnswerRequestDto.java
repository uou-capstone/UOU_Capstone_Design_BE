package io.github.uou_capstone.aiplatform.domain.course.lecture.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class LectureStreamAnswerRequestDto {

    @NotBlank(message = "aiQuestionId는 필수입니다.")
    private String aiQuestionId;

    @NotBlank(message = "answer는 필수입니다.")
    private String answer;
}

