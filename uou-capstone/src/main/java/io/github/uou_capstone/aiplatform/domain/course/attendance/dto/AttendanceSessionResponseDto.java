package io.github.uou_capstone.aiplatform.domain.course.attendance.dto;

import io.github.uou_capstone.aiplatform.domain.course.attendance.entity.AttendanceSession;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Getter
public class AttendanceSessionResponseDto {

    private final Long sessionId;
    private final Long courseId;
    private final Long lectureId;
    private final String title;
    private final LocalDate sessionDate;
    private final LocalTime startTime;
    private final LocalTime endTime;
    private final Long createdByTeacherId;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public AttendanceSessionResponseDto(AttendanceSession s) {
        this.sessionId = s.getId();
        this.courseId = s.getCourse().getId();
        this.lectureId = s.getLecture() != null ? s.getLecture().getId() : null;
        this.title = s.getTitle();
        this.sessionDate = s.getSessionDate();
        this.startTime = s.getStartTime();
        this.endTime = s.getEndTime();
        this.createdByTeacherId = s.getCreatedBy().getId();
        this.createdAt = s.getCreatedAt();
        this.updatedAt = s.getUpdatedAt();
    }
}
