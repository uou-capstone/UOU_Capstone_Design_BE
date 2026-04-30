package io.github.uou_capstone.aiplatform.domain.course.dto;

import io.github.uou_capstone.aiplatform.domain.course.entity.CourseJoinRequest;
import io.github.uou_capstone.aiplatform.domain.course.entity.CourseJoinRequestStatus;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class CourseJoinRequestResponseDto {

    private final Long requestId;
    private final Long courseId;
    private final String courseTitle;
    private final CourseJoinRequestStatus status;
    private final LocalDateTime requestedAt;

    public CourseJoinRequestResponseDto(CourseJoinRequest request) {
        this.requestId = request.getId();
        this.courseId = request.getCourse().getId();
        this.courseTitle = request.getCourse().getTitle();
        this.status = request.getStatus();
        this.requestedAt = request.getCreatedAt();
    }
}
