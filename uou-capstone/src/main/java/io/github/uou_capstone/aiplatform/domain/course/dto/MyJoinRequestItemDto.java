package io.github.uou_capstone.aiplatform.domain.course.dto;

import io.github.uou_capstone.aiplatform.domain.course.entity.CourseJoinRequest;
import io.github.uou_capstone.aiplatform.domain.course.entity.CourseJoinRequestStatus;
import lombok.Getter;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Getter
public class MyJoinRequestItemDto {

    private final Long requestId;
    private final Long courseId;
    private final String courseTitle;
    private final CourseJoinRequestStatus status;
    private final OffsetDateTime requestedAt;
    private final OffsetDateTime updatedAt;

    public MyJoinRequestItemDto(CourseJoinRequest request) {
        this.requestId = request.getId();
        this.courseId = request.getCourse().getId();
        this.courseTitle = request.getCourse().getTitle();
        this.status = request.getStatus();
        this.requestedAt = toUtcOffset(request.getCreatedAt());
        this.updatedAt = toUtcOffset(request.getUpdatedAt());
    }

    private static OffsetDateTime toUtcOffset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
