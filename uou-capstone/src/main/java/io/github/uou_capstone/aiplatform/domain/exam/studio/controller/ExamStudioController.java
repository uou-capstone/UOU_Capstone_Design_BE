package io.github.uou_capstone.aiplatform.domain.exam.studio.controller;

import io.github.uou_capstone.aiplatform.domain.exam.studio.dto.ExamStudioChatRequest;
import io.github.uou_capstone.aiplatform.domain.exam.studio.dto.ExamStudioPdfContextRequest;
import io.github.uou_capstone.aiplatform.domain.exam.studio.service.ExamStudioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * Exam Studio — 교사가 PDF 컨텍스트 기반으로 AI 와 대화하며 시험 문항 작성.
 *
 * <p>두 단계:
 * <ol>
 *   <li>{@code POST /pdf-context} — Material 의 PDF 를 FastAPI 에 등록하고 {@code contextId} 받기.</li>
 *   <li>{@code POST /chat/stream} — {@code contextId} 와 메시지로 SSE 스트림 대화.</li>
 * </ol>
 */
@Tag(name = "시험 작성 Studio", description = "PDF 기반 대화형 시험 작성 보조 (교사)")
@RestController
@RequestMapping("/api/courses/{courseId}/exam-studio")
@RequiredArgsConstructor
public class ExamStudioController {

    private final ExamStudioService examStudioService;

    @Operation(summary = "PDF Context 발급",
            description = "Material(PDF)을 FastAPI에 등록하고 contextId를 발급한다. " +
                    "contextId는 process-memory cache 기반(best-effort)이므로 만료 시 재발급 필요.")
    @PostMapping("/pdf-context")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Map<String, Object>> issuePdfContext(
            @PathVariable Long courseId,
            @Valid @RequestBody ExamStudioPdfContextRequest request) {
        return ResponseEntity.ok(examStudioService.issuePdfContext(courseId, request));
    }

    @Operation(summary = "AI 대화 (SSE 스트림)",
            description = "contextId와 메시지로 AI에게 시험 문항 작성을 보조 요청. " +
                    "응답 done.data.operations[]를 FE가 시험 초안에 적용한다.")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('TEACHER')")
    public Flux<ServerSentEvent<Map<String, Object>>> streamChat(
            @PathVariable Long courseId,
            @Valid @RequestBody ExamStudioChatRequest request,
            HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        return examStudioService.streamChat(courseId, request);
    }
}
