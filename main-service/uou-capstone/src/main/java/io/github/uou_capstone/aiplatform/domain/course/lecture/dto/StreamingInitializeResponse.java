package io.github.uou_capstone.aiplatform.domain.course.lecture.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamingInitializeResponse {
    private String status;
    private Long lectureId;
    private Integer totalChapters;
    private List<StreamingChapterDto> chapters;
}

