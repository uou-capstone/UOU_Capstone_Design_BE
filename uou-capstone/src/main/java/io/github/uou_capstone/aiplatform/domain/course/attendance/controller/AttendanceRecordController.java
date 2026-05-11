package io.github.uou_capstone.aiplatform.domain.course.attendance.controller;

import io.github.uou_capstone.aiplatform.domain.course.attendance.dto.AttendanceRecordResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.attendance.dto.AttendanceRecordsBulkUpsertRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.attendance.dto.CourseAttendanceMatrixResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.attendance.dto.StudentAttendanceSummaryResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.attendance.service.AttendanceRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "출석부 API", description = "출석 record 조회/일괄 저장 + 학생 본인/교사 매트릭스")
@RestController
@RequestMapping("/api/courses/{courseId}/attendance")
@RequiredArgsConstructor
public class AttendanceRecordController {

    private final AttendanceRecordService recordService;

    @Operation(summary = "회차별 출석 record 조회 (교사)")
    @GetMapping("/sessions/{sessionId}/records")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<List<AttendanceRecordResponseDto>> getRecords(
            @PathVariable Long courseId,
            @PathVariable Long sessionId) {
        return ResponseEntity.ok(recordService.getRecords(courseId, sessionId));
    }

    @Operation(summary = "출석부 일괄 저장 (교사)",
            description = "분산 락 + 트랜잭션으로 동시 PUT 직렬화. 신규 insert 분기는 ACTIVE 수강생 검증.")
    @PutMapping("/sessions/{sessionId}/records")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Void> bulkUpsert(
            @PathVariable Long courseId,
            @PathVariable Long sessionId,
            @Valid @RequestBody AttendanceRecordsBulkUpsertRequestDto dto) {
        recordService.bulkUpsert(courseId, sessionId, dto);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "본인 출석 요약 (학생)",
            description = "회차별 status 시계열 + presentRatio (PRESENT / 전체 세션 수)")
    @GetMapping("/me")
    @PreAuthorize("hasAuthority('STUDENT')")
    public ResponseEntity<StudentAttendanceSummaryResponseDto> getMySummary(
            @PathVariable Long courseId) {
        return ResponseEntity.ok(recordService.getMySummary(courseId));
    }

    @Operation(summary = "강의실 출석 매트릭스 (교사)",
            description = "sessions: 비페이징 헤더 / students: PageResponse")
    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<CourseAttendanceMatrixResponseDto> getMatrix(
            @PathVariable Long courseId,
            @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(recordService.getMatrix(courseId, pageable));
    }
}
