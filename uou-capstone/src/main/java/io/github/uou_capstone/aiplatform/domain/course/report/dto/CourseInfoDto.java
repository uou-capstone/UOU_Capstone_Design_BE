package io.github.uou_capstone.aiplatform.domain.course.report.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CourseInfoDto {
    private final Long courseId;
    private final String title;
}
