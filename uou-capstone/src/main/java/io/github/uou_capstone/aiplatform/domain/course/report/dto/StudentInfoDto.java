package io.github.uou_capstone.aiplatform.domain.course.report.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class StudentInfoDto {
    private final Long studentId;
    private final Long userId;
    private final String studentName;
    private final String email;
}
