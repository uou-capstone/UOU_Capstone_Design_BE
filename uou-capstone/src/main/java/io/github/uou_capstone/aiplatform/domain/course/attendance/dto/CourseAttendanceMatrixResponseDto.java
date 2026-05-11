package io.github.uou_capstone.aiplatform.domain.course.attendance.dto;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.domain.course.attendance.entity.AttendanceStatus;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 교사용 강의실 출석 매트릭스.
 * - sessions: 비페이징 회차 헤더
 * - students: 학생 페이징 (각 row 에 회차별 status map + presentRatio)
 *
 * <p>presentRatio 정의: PRESENT 개수 / 전체 세션 수.
 */
@Getter
public class CourseAttendanceMatrixResponseDto {

    private final List<SessionHeaderDto> sessions;
    private final PageResponse<StudentAttendanceMatrixRowDto> students;

    public CourseAttendanceMatrixResponseDto(List<SessionHeaderDto> sessions,
                                             PageResponse<StudentAttendanceMatrixRowDto> students) {
        this.sessions = sessions;
        this.students = students;
    }

    @Getter
    public static class SessionHeaderDto {
        private final Long sessionId;
        private final String title;
        private final LocalDate sessionDate;

        public SessionHeaderDto(Long sessionId, String title, LocalDate sessionDate) {
            this.sessionId = sessionId;
            this.title = title;
            this.sessionDate = sessionDate;
        }
    }

    @Getter
    public static class StudentAttendanceMatrixRowDto {
        private final Long studentId;
        private final String name;
        private final Map<Long, AttendanceStatus> records;
        private final double presentRatio;

        public StudentAttendanceMatrixRowDto(Long studentId,
                                             String name,
                                             Map<Long, AttendanceStatus> records,
                                             double presentRatio) {
            this.studentId = studentId;
            this.name = name;
            this.records = records;
            this.presentRatio = presentRatio;
        }
    }
}
