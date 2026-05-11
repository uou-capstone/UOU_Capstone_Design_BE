package io.github.uou_capstone.aiplatform.domain.course.report.criteria.controller;

import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.CriteriaAssistantRequest;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.CriterionCreateRequest;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.CriterionResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.CriterionUpdateRequest;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.service.CourseReportCriteriaAssistantService;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.service.CourseReportCriterionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * Course Report Criteria — 교사가 강의실 평가 기준을 관리(CRUD)하고 AI 추천을 받는다.
 */
@Tag(name = "강의실 리포트 평가 기준",
        description = "교사가 강의실 단위 평가 기준(label/description/weight)을 관리하고 AI 추천을 받는다.")
@RestController
@RequestMapping("/api/courses/{courseId}/reports/criteria")
@RequiredArgsConstructor
public class CourseReportCriteriaController {

    private final CourseReportCriterionService criterionService;
    private final CourseReportCriteriaAssistantService assistantService;

    @Operation(summary = "평가 기준 목록")
    @GetMapping
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<List<CriterionResponse>> list(@PathVariable Long courseId) {
        return ResponseEntity.ok(criterionService.list(courseId));
    }

    @Operation(summary = "평가 기준 추가")
    @PostMapping
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<CriterionResponse> create(
            @PathVariable Long courseId,
            @Valid @RequestBody CriterionCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(criterionService.create(courseId, req));
    }

    @Operation(summary = "평가 기준 수정")
    @PatchMapping("/{criterionId}")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<CriterionResponse> update(
            @PathVariable Long courseId,
            @PathVariable Long criterionId,
            @Valid @RequestBody CriterionUpdateRequest req) {
        return ResponseEntity.ok(criterionService.update(courseId, criterionId, req));
    }

    @Operation(summary = "평가 기준 삭제")
    @DeleteMapping("/{criterionId}")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Void> delete(
            @PathVariable Long courseId,
            @PathVariable Long criterionId) {
        criterionService.delete(courseId, criterionId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "평가 기준 AI 추천 (SSE 스트림)",
            description = "강의 컨텍스트와 기존 기준을 보고 AI 가 평가 기준 N개를 추천. 중간 이벤트 criterion_suggestion 으로 1개씩 도착.")
    @PostMapping(value = "/assistant/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('TEACHER')")
    public Flux<ServerSentEvent<Map<String, Object>>> streamAssistant(
            @PathVariable Long courseId,
            @Valid @RequestBody(required = false) CriteriaAssistantRequest req,
            HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        return assistantService.streamAssistant(courseId, req);
    }
}
