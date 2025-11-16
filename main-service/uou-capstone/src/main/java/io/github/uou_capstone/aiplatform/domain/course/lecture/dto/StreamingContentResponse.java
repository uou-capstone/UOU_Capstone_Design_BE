package io.github.uou_capstone.aiplatform.domain.course.lecture.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamingContentResponse {
    private String status;
    private Long lectureId;
    private String contentType;
    private String contentData;
    private String chapterTitle;
    private Boolean hasMore;
    private Boolean waitingForAnswer;
    private String aiQuestionId;
}

