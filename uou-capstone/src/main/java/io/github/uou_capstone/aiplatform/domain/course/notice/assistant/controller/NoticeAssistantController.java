package io.github.uou_capstone.aiplatform.domain.course.notice.assistant.controller;

import io.github.uou_capstone.aiplatform.domain.course.notice.assistant.dto.NoticeAssistantRequest;
import io.github.uou_capstone.aiplatform.domain.course.notice.assistant.service.NoticeAssistantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

@Tag(name = "Notice AI Assistant", description = "Notice draft assistant for teachers.")
@RestController
@RequestMapping("/api/courses/{courseId}/notices/assistant")
@RequiredArgsConstructor
public class NoticeAssistantController {

    private final NoticeAssistantService noticeAssistantService;

    @Operation(summary = "Stream notice draft assistant",
            description = "Streams a notice draft using course context and recent notices.")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('TEACHER')")
    public Flux<ServerSentEvent<Map<String, Object>>> stream(
            @PathVariable Long courseId,
            @Valid @RequestBody NoticeAssistantRequest request,
            HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        return noticeAssistantService.streamAssistant(courseId, request);
    }
}
