package io.github.uou_capstone.aiplatform.domain.course.report.controller;

import io.github.uou_capstone.aiplatform.domain.course.report.dto.CourseStudentReportDetailResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.CourseStudentReportListResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.service.CourseStudentReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "강의실 리포트(Course Report) API",
        description = "선생님이 자신의 강의실에 소속된 학생들의 학습 성과/역량 리포트를 조회")
@RestController
@RequestMapping("/api/courses/{courseId}/reports")
@RequiredArgsConstructor
public class CourseReportController {

    private final CourseStudentReportService courseStudentReportService;

    @Operation(summary = "강의실 학생 리포트 리스트",
            description = "강의실 학생 카드 목록을 조회합니다. 이름 검색, 정렬, 상태 필터를 지원합니다.")
    @GetMapping("/students")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<CourseStudentReportListResponse> getStudentReportList(
            @PathVariable Long courseId,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "direction", required = false) String direction,
            @RequestParam(value = "status", required = false) String status
    ) {
        return ResponseEntity.ok(courseStudentReportService.getStudentReportList(
                courseId, q, sortBy, direction, status));
    }

    @Operation(summary = "강의실 학생 상세 리포트",
            description = "한 학생의 강의실 단위 활동/역량/근거/서술 리포트를 조회합니다.")
    @GetMapping("/students/{studentId}")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<CourseStudentReportDetailResponse> getStudentReportDetail(
            @PathVariable Long courseId,
            @PathVariable Long studentId
    ) {
        return ResponseEntity.ok(courseStudentReportService.getStudentReportDetail(courseId, studentId));
    }
}
