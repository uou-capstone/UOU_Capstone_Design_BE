package io.github.uou_capstone.aiplatform.domain.course.report.criteria.controller;

import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.CriteriaAssistantRequest;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.CriteriaAssistantChatRequest;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.CriteriaSummaryResponse;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@Tag(name = "Course Report Criteria",
        description = "Teacher-managed report criteria and AI suggestions for a course.")
@RestController
@RequestMapping("/api/courses/{courseId}/reports/criteria")
@RequiredArgsConstructor
public class CourseReportCriteriaController {

    private final CourseReportCriterionService criterionService;
    private final CourseReportCriteriaAssistantService assistantService;

    @Operation(summary = "List report criteria")
    @GetMapping
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<List<CriterionResponse>> list(@PathVariable Long courseId) {
        return ResponseEntity.ok(criterionService.list(courseId));
    }

    @Operation(summary = "Get report criteria summary")
    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<CriteriaSummaryResponse> summary(@PathVariable Long courseId) {
        return ResponseEntity.ok(criterionService.summary(courseId));
    }

    @Operation(summary = "Create report criterion")
    @PostMapping
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<CriterionResponse> create(
            @PathVariable Long courseId,
            @Valid @RequestBody CriterionCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(criterionService.create(courseId, req));
    }

    @Operation(summary = "Update report criterion")
    @PatchMapping("/{criterionId}")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<CriterionResponse> update(
            @PathVariable Long courseId,
            @PathVariable Long criterionId,
            @Valid @RequestBody CriterionUpdateRequest req) {
        return ResponseEntity.ok(criterionService.update(courseId, criterionId, req));
    }

    @Operation(summary = "Delete report criterion")
    @DeleteMapping("/{criterionId}")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Void> delete(
            @PathVariable Long courseId,
            @PathVariable Long criterionId) {
        criterionService.delete(courseId, criterionId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Stream report criteria AI suggestions",
            description = "Streams criterion_suggestion events based on course context and existing criteria.")
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

    @Operation(summary = "Stream report criteria AI chat",
            description = "Streams reply and operation suggestions for report criteria changes. CRUD is applied separately by FE/Spring confirmation.")
    @PostMapping(value = "/assistant/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('TEACHER')")
    public Flux<ServerSentEvent<Map<String, Object>>> streamAssistantChat(
            @PathVariable Long courseId,
            @Valid @RequestBody CriteriaAssistantChatRequest req,
            HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        return assistantService.streamChat(courseId, req);
    }
}
