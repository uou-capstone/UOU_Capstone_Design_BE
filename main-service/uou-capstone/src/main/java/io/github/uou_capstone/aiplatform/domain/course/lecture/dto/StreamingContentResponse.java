package io.github.uou_capstone.aiplatform.domain.course.lecture.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamingContentResponse { //get_next_content 응답 그대로 매핑.
    private String status;
    private Long lectureId;
    private String contentType;
    private String contentData;
    private String chapterTitle;
    private Boolean hasMore;
    private Boolean waitingForAnswer;
    private String aiQuestionId;
}

