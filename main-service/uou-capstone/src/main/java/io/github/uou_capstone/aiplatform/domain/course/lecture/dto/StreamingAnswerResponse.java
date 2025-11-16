package io.github.uou_capstone.aiplatform.domain.course.lecture.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamingAnswerResponse {
    private String status;
    private Long lectureId;
    private String aiQuestionId;
    private String question;
    private String chapterTitle;
    private String supplementary;
    private Boolean canContinue;
}

