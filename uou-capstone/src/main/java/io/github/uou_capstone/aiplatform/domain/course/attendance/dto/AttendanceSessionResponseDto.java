package io.github.uou_capstone.aiplatform.domain.course.attendance.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.github.uou_capstone.aiplatform.domain.course.attendance.entity.AttendanceSession;
import io.swagger.v3.oas.annotations.media.Schema;
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

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm:ss")
    @Schema(type = "string", format = "time", example = "10:00:00",
            description = "출석 회차 시작 시간 (HH:mm:ss)")
    private final LocalTime startTime;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm:ss")
    @Schema(type = "string", format = "time", example = "12:00:00",
            description = "출석 회차 종료 시간 (HH:mm:ss)")
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
