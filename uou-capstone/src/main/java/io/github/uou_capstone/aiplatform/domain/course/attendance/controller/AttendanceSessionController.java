package io.github.uou_capstone.aiplatform.domain.course.attendance.controller;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.domain.course.attendance.dto.AttendanceSessionCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.attendance.dto.AttendanceSessionResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.attendance.dto.AttendanceSessionUpdateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.attendance.service.AttendanceSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "출석 회차 API", description = "강의실 출석 회차 CRUD (교사 전용)")
@RestController
@RequestMapping("/api/courses/{courseId}/attendance/sessions")
@RequiredArgsConstructor
public class AttendanceSessionController {

    private final AttendanceSessionService sessionService;

    @Operation(summary = "출석 회차 목록 (교사)")
    @GetMapping
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<PageResponse<AttendanceSessionResponseDto>> list(
            @PathVariable Long courseId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(sessionService.listSessions(courseId, pageable));
    }

    @Operation(summary = "출석 회차 생성 (교사)",
            description = "회차 생성 시 ACTIVE 수강생 전체에 대해 ABSENT record 가 자동 생성됩니다. "
                    + "lectureId 가 있으면 같은 강의실 lecture 인지 검증합니다.")
    @PostMapping
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<AttendanceSessionResponseDto> create(
            @PathVariable Long courseId,
            @Valid @RequestBody AttendanceSessionCreateRequestDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(sessionService.createSession(courseId, dto));
    }

    @Operation(summary = "출석 회차 상세 (교사)")
    @GetMapping("/{sessionId}")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<AttendanceSessionResponseDto> get(
            @PathVariable Long courseId,
            @PathVariable Long sessionId) {
        return ResponseEntity.ok(sessionService.getSession(courseId, sessionId));
    }

    @Operation(summary = "출석 회차 메타 수정 (교사)",
            description = "title / sessionDate / startTime / endTime / lectureId. lectureId 변경 시 같은 강의실 검증.")
    @PatchMapping("/{sessionId}")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<AttendanceSessionResponseDto> update(
            @PathVariable Long courseId,
            @PathVariable Long sessionId,
            @Valid @RequestBody AttendanceSessionUpdateRequestDto dto) {
        return ResponseEntity.ok(sessionService.updateSession(courseId, sessionId, dto));
    }

    @Operation(summary = "출석 회차 삭제 (교사)", description = "cascade 로 records 도 함께 삭제됩니다.")
    @DeleteMapping("/{sessionId}")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Void> delete(
            @PathVariable Long courseId,
            @PathVariable Long sessionId) {
        sessionService.deleteSession(courseId, sessionId);
        return ResponseEntity.noContent().build();
    }
}
