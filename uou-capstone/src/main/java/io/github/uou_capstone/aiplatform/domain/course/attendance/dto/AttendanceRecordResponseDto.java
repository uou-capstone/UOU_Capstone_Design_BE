package io.github.uou_capstone.aiplatform.domain.course.attendance.dto;

import io.github.uou_capstone.aiplatform.domain.course.attendance.entity.AttendanceRecord;
import io.github.uou_capstone.aiplatform.domain.course.attendance.entity.AttendanceStatus;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class AttendanceRecordResponseDto {

    private final Long recordId;
    private final Long sessionId;
    private final Long studentId;
    private final String studentName;
    private final AttendanceStatus status;
    private final LocalDateTime markedAt;
    private final Long markedByTeacherId;
    private final String note;

    public AttendanceRecordResponseDto(AttendanceRecord r) {
        this.recordId = r.getId();
        this.sessionId = r.getSession().getId();
        this.studentId = r.getStudent().getId();
        this.studentName = r.getStudent().getUser().getFullName();
        this.status = r.getStatus();
        this.markedAt = r.getMarkedAt();
        this.markedByTeacherId = r.getMarkedBy().getId();
        this.note = r.getNote();
    }
}
