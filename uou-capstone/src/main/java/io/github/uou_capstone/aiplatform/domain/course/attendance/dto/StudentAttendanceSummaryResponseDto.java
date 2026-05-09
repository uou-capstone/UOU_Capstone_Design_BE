package io.github.uou_capstone.aiplatform.domain.course.attendance.dto;

import io.github.uou_capstone.aiplatform.domain.course.attendance.entity.AttendanceStatus;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/**
 * 학생 본인의 강의실 출석 요약. presentRatio = PRESENT / 전체 세션 수.
 */
@Getter
public class StudentAttendanceSummaryResponseDto {

    private final Long courseId;
    private final int totalSessions;
    private final int presentCount;
    private final int lateCount;
    private final int absentCount;
    private final int excusedCount;
    private final double presentRatio;
    private final List<SessionStatusItem> sessions;

    public StudentAttendanceSummaryResponseDto(Long courseId,
                                               int totalSessions,
                                               int presentCount,
                                               int lateCount,
                                               int absentCount,
                                               int excusedCount,
                                               List<SessionStatusItem> sessions) {
        this.courseId = courseId;
        this.totalSessions = totalSessions;
        this.presentCount = presentCount;
        this.lateCount = lateCount;
        this.absentCount = absentCount;
        this.excusedCount = excusedCount;
        this.presentRatio = totalSessions == 0 ? 0.0 : (double) presentCount / totalSessions;
        this.sessions = sessions;
    }

    @Getter
    public static class SessionStatusItem {
        private final Long sessionId;
        private final String title;
        private final LocalDate sessionDate;
        private final AttendanceStatus status;

        public SessionStatusItem(Long sessionId, String title, LocalDate sessionDate, AttendanceStatus status) {
            this.sessionId = sessionId;
            this.title = title;
            this.sessionDate = sessionDate;
            this.status = status;
        }
    }
}
