package io.github.uou_capstone.aiplatform.domain.course.lecture.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamingInitializeResponse { //스트리밍 초기화 결과를 프런트에 전달.
    private String status;
    private Long lectureId;
    private Integer totalChapters;
    private List<StreamingChapterDto> chapters;
}

