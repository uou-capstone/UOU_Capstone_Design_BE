package io.github.uou_capstone.aiplatform.domain.course.dto;

import io.github.uou_capstone.aiplatform.domain.course.entity.CourseJoinRequest;
import io.github.uou_capstone.aiplatform.domain.course.entity.CourseJoinRequestStatus;
import lombok.Getter;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Getter
public class CourseJoinRequestListItemDto {

    private final Long requestId;
    private final Long studentId;
    private final String studentName;
    private final String studentEmail;
    private final CourseJoinRequestStatus status;
    private final OffsetDateTime requestedAt;

    public CourseJoinRequestListItemDto(CourseJoinRequest request) {
        this.requestId = request.getId();
        this.studentId = request.getStudent().getId();
        this.studentName = request.getStudent().getUser().getFullName();
        this.studentEmail = request.getStudent().getUser().getEmail();
        this.status = request.getStatus();
        this.requestedAt = toUtcOffset(request.getCreatedAt());
    }

    private static OffsetDateTime toUtcOffset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
