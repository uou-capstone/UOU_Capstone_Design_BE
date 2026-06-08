package io.github.uou_capstone.aiplatform.domain.course.report.controller;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.classroom.dto.ClassroomReportResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.classroom.service.ClassroomReportService;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ClassroomLearningFlowResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.StudentReportActivitySummaryResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.StudentReportDetailResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.StudentReportListItem;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.StudentAiReportContextResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.service.CourseReportSupplementService;
import io.github.uou_capstone.aiplatform.domain.course.report.service.CourseStudentReportService;
import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.dto.StudentReportAnalysisRequest;
import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.dto.StudentReportAnalysisResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.service.StudentReportAnalysisService;
import io.github.uou_capstone.aiplatform.domain.course.report.studentchat.dto.StudentReportChatHistoryItem;
import io.github.uou_capstone.aiplatform.domain.course.report.studentchat.dto.StudentReportChatRequest;
import io.github.uou_capstone.aiplatform.domain.course.report.studentchat.service.StudentReportChatPersistenceService;
import io.github.uou_capstone.aiplatform.domain.course.report.studentchat.service.StudentReportChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

@Tag(name = "Course Report API",
        description = "Teacher-facing course report, student report, classroom report, and AI analysis APIs.")
@RestController
@RequestMapping("/api/courses/{courseId}/reports")
@RequiredArgsConstructor
public class CourseReportController {

    private final CourseStudentReportService courseStudentReportService;
    private final CourseReportSupplementService courseReportSupplementService;
    private final ClassroomReportService classroomReportService;
    private final StudentReportChatService studentReportChatService;
    private final StudentReportChatPersistenceService studentReportChatPersistenceService;
    private final StudentReportAnalysisService studentReportAnalysisService;

    @Operation(summary = "List student reports")
    @GetMapping("/students")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<PageResponse<StudentReportListItem>> getStudentReportList(
            @PathVariable Long courseId,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status,
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        return ResponseEntity.ok(courseStudentReportService.getStudentReportList(
                courseId, q, status, pageable));
    }

    @Operation(summary = "Get student report detail")
    @GetMapping("/students/{studentId}")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<StudentReportDetailResponse> getStudentReportDetail(
            @PathVariable Long courseId,
            @PathVariable Long studentId
    ) {
        return ResponseEntity.ok(courseStudentReportService.getStudentReportDetail(courseId, studentId));
    }

    @Operation(summary = "Get student activity summary")
    @GetMapping("/students/{studentId}/activity-summary")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<StudentReportActivitySummaryResponse> getStudentActivitySummary(
            @PathVariable Long courseId,
            @PathVariable Long studentId
    ) {
        return ResponseEntity.ok(courseReportSupplementService.getStudentActivitySummary(courseId, studentId));
    }

    @Operation(summary = "Stream student report chat")
    @PostMapping(value = "/students/{studentId}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('TEACHER')")
    public Flux<ServerSentEvent<Map<String, Object>>> streamStudentChat(
            @PathVariable Long courseId,
            @PathVariable Long studentId,
            @Valid @RequestBody StudentReportChatRequest request,
            HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        return studentReportChatService.streamChat(courseId, studentId, request);
    }

    @Operation(summary = "Get student report chat history")
    @GetMapping("/students/{studentId}/chat/history")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<PageResponse<StudentReportChatHistoryItem>> getStudentChatHistory(
            @PathVariable Long courseId,
            @PathVariable Long studentId,
            @RequestParam(value = "sessionId", required = false) Long sessionId,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        return ResponseEntity.ok(studentReportChatPersistenceService.getHistory(
                courseId, studentId, sessionId, pageable));
    }

    @Operation(summary = "Get student AI analysis context",
            description = "Returns the context shape used by FastAPI student report analysis. Use /analyze or /analyze/stream to execute analysis through Spring.")
    @GetMapping("/students/{studentId}/ai-context")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<StudentAiReportContextResponse> getStudentAiReportContext(
            @PathVariable Long courseId,
            @PathVariable Long studentId
    ) {
        return ResponseEntity.ok(courseStudentReportService.getStudentAiReportContext(courseId, studentId));
    }

    @Operation(summary = "Get saved student AI analysis")
    @GetMapping("/students/{studentId}/analysis")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<StudentReportAnalysisResponse> getStudentAnalysis(
            @PathVariable Long courseId,
            @PathVariable Long studentId
    ) {
        return studentReportAnalysisService.get(courseId, studentId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(summary = "Analyze student AI report",
            description = "Spring builds student context including reportCriteria, calls FastAPI /api/v3/report/student/analyze, and stores the result.")
    @PostMapping("/students/{studentId}/analyze")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Map<String, Object>> analyzeStudentReport(
            @PathVariable Long courseId,
            @PathVariable Long studentId,
            @RequestBody(required = false) StudentReportAnalysisRequest request
    ) {
        return ResponseEntity.ok(studentReportAnalysisService.analyze(courseId, studentId, request));
    }

    @Operation(summary = "Stream student AI report analysis",
            description = "Spring builds student context including reportCriteria, calls FastAPI /api/v3/report/student/analyze/stream, and stores the done event result.")
    @PostMapping(value = "/students/{studentId}/analyze/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('TEACHER')")
    public Flux<ServerSentEvent<Map<String, Object>>> analyzeStudentReportStream(
            @PathVariable Long courseId,
            @PathVariable Long studentId,
            @RequestBody(required = false) StudentReportAnalysisRequest request,
            HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        return studentReportAnalysisService.analyzeStream(courseId, studentId, request);
    }

    @Operation(summary = "Get saved classroom report")
    @GetMapping("/classroom")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<ClassroomReportResponse> getClassroomReport(@PathVariable Long courseId) {
        return classroomReportService.get(courseId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(summary = "Get classroom learning flow")
    @GetMapping("/classroom/flow")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<ClassroomLearningFlowResponse> getClassroomFlow(@PathVariable Long courseId) {
        return ResponseEntity.ok(courseReportSupplementService.getClassroomFlow(courseId));
    }

    @Operation(summary = "Analyze classroom report")
    @PostMapping("/classroom/analyze")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Map<String, Object>> analyzeClassroomReport(@PathVariable Long courseId) {
        return ResponseEntity.ok(classroomReportService.analyze(courseId));
    }

    @Operation(summary = "Stream classroom report analysis")
    @PostMapping(value = "/classroom/analyze/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('TEACHER')")
    public Flux<ServerSentEvent<Map<String, Object>>> analyzeClassroomReportStream(
            @PathVariable Long courseId,
            HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        return classroomReportService.analyzeStream(courseId);
    }
}
