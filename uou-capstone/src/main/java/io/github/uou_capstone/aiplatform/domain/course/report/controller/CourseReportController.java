package io.github.uou_capstone.aiplatform.domain.course.report.controller;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.classroom.dto.ClassroomReportResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.classroom.service.ClassroomReportService;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.StudentReportDetailResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.StudentReportListItem;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.StudentAiReportContextResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.service.CourseStudentReportService;
import io.github.uou_capstone.aiplatform.domain.course.report.studentchat.dto.StudentReportChatRequest;
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

@Tag(name = "강의실 리포트(Course Report) API",
        description = "선생님이 자신의 강의실에 소속된 학생들의 학습 성과/역량 리포트를 조회")
@RestController
@RequestMapping("/api/courses/{courseId}/reports")
@RequiredArgsConstructor
public class CourseReportController {

    private final CourseStudentReportService courseStudentReportService;
    private final ClassroomReportService classroomReportService;
    private final StudentReportChatService studentReportChatService;

    @Operation(summary = "강의실 학생 리포트 리스트",
            description = "강의실 학생 항목 목록을 페이지 단위로 조회합니다. 정렬 허용 필드: name / averageScore / latestActivity / reportStatus. 기본 정렬: name,asc. 이름 검색(q), 상태 필터(status) 지원.")
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

    @Operation(summary = "강의실 학생 상세 리포트",
            description = "한 학생의 강의실 단위 활동/역량/근거/서술 리포트를 조회합니다.")
    @GetMapping("/students/{studentId}")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<StudentReportDetailResponse> getStudentReportDetail(
            @PathVariable Long courseId,
            @PathVariable Long studentId
    ) {
        return ResponseEntity.ok(courseStudentReportService.getStudentReportDetail(courseId, studentId));
    }

    @Operation(summary = "학생 리포트 Chatbot (SSE 스트림)",
            description = "교사가 특정 학생 리포트에 대해 follow-up 질문을 보낸다. " +
                    "Spring 이 학생 context + report 를 모아 FastAPI /bridge/report/student_chat_stream 으로 전달.")
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

    @Operation(summary = "학생 AI 분석 Context",
            description = "FastAPI POST /api/v3/report/student/analyze 가 입력으로 받는 분석용 Context DTO 를 반환합니다. " +
                    "Spring 은 FastAPI 를 호출하지 않습니다 — FE/AI 클라이언트가 이 응답을 받아 직접 FastAPI 로 전달합니다.")
    @GetMapping("/students/{studentId}/ai-context")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<StudentAiReportContextResponse> getStudentAiReportContext(
            @PathVariable Long courseId,
            @PathVariable Long studentId
    ) {
        return ResponseEntity.ok(courseStudentReportService.getStudentAiReportContext(courseId, studentId));
    }

    // ===========================================================================
    // Classroom Report — 강의실 종합 분석
    // ===========================================================================

    @Operation(summary = "강의실 종합 리포트 조회",
            description = "저장된 강의실 종합 리포트(1 course = 1 row)를 반환. 미생성 시 204.")
    @GetMapping("/classroom")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<ClassroomReportResponse> getClassroomReport(@PathVariable Long courseId) {
        return classroomReportService.get(courseId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(summary = "강의실 종합 리포트 분석 (동기)",
            description = "FastAPI /bridge/report/classroom_analyze 호출, 결과를 UPSERT 저장 후 분석 JSON 반환.")
    @PostMapping("/classroom/analyze")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Map<String, Object>> analyzeClassroomReport(@PathVariable Long courseId) {
        return ResponseEntity.ok(classroomReportService.analyze(courseId));
    }

    @Operation(summary = "강의실 종합 리포트 분석 (SSE 스트림)",
            description = "FastAPI /bridge/report/classroom_analyze_stream 호출. done 이벤트 수신 시 UPSERT.")
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
