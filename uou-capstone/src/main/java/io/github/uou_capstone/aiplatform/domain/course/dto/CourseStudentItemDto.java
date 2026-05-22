package io.github.uou_capstone.aiplatform.domain.course.dto;

import io.github.uou_capstone.aiplatform.domain.course.entity.Enrollment;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Getter
public class CourseStudentItemDto {

    private final Long enrollmentId;
    private final Long studentId;
    private final String studentName;
    private final String studentEmail;
    @Schema(type = "string", format = "date-time", example = "2026-05-22T12:34:56Z")
    private final OffsetDateTime enrolledAt;

    public CourseStudentItemDto(Enrollment enrollment) {
        this.enrollmentId = enrollment.getId();
        this.studentId = enrollment.getStudent().getId();
        this.studentName = enrollment.getStudent().getUser().getFullName();
        this.studentEmail = enrollment.getStudent().getUser().getEmail();
        this.enrolledAt = toUtcOffset(enrollment.getCreatedAt());
    }

    private static OffsetDateTime toUtcOffset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
