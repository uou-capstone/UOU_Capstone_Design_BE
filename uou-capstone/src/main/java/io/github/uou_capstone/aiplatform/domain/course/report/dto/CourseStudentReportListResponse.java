package io.github.uou_capstone.aiplatform.domain.course.report.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 강의실 학생 리포트 리스트 응답 — 강의실 메타 + 학생 카드 목록.
 */
@Getter
@Builder
public class CourseStudentReportListResponse {
    private final Long courseId;
    private final String courseTitle;
    private final int totalStudents;
    private final List<StudentReportCardDto> students;
}
