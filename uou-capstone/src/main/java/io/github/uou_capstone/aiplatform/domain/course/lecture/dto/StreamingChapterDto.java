package io.github.uou_capstone.aiplatform.domain.course.lecture.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class StreamingChapterDto {
    private String title;
    private Integer startPage;
    private Integer endPage;
}

