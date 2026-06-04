package io.github.uou_capstone.aiplatform.domain.course.discussion.assistant.controller;

import io.github.uou_capstone.aiplatform.domain.course.discussion.assistant.dto.DiscussionAssistantRequest;
import io.github.uou_capstone.aiplatform.domain.course.discussion.assistant.service.DiscussionAssistantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * Discussion AI Assistant — 학생/교사가 토론 게시글 초안 작성을 AI 보조로 진행.
 *
 * <p>SSE 이벤트: {@code thought_delta}, {@code answer_delta}, {@code done}, {@code error}, {@code heartbeat}.
 */
@Tag(name = "토론 AI Assistant", description = "토론 게시글 초안 작성 보조 (학생/교사)")
@RestController
@RequestMapping("/api/courses/{courseId}/discussions/assistant")
@RequiredArgsConstructor
public class DiscussionAssistantController {

    private final DiscussionAssistantService discussionAssistantService;

    @Operation(summary = "토론 글 초안 생성 (SSE 스트림)",
            description = "강의 컨텍스트(최근 5개 게시글 title/category)와 사용자 입력(topic/category/previousDraft)을 기반으로 " +
                    "AI 가 글 초안을 스트리밍한다. FastAPI /bridge/discussion_assistant_stream 호출.")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public Flux<ServerSentEvent<Map<String, Object>>> stream(
            @PathVariable Long courseId,
            @Valid @RequestBody DiscussionAssistantRequest request,
            HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        return discussionAssistantService.streamAssistant(courseId, request);
    }
}
