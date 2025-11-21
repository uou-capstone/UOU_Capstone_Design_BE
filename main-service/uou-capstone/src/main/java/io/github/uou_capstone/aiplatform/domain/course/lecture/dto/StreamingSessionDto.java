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
public class StreamingSessionDto { //get_session 응답 매핑
    private String status;
    private Long lectureId;
    private String serviceStatus;
    private Object chapters;
    private Object questions;
    private String createdAt;
    private String updatedAt;
    private Object error;
}

