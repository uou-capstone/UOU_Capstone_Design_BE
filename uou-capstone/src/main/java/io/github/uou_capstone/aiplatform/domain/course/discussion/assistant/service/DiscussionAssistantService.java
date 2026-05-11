package io.github.uou_capstone.aiplatform.domain.course.discussion.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.domain.course.discussion.assistant.dto.DiscussionAssistantRequest;
import io.github.uou_capstone.aiplatform.domain.course.discussion.entity.Discussion;
import io.github.uou_capstone.aiplatform.domain.course.discussion.repository.DiscussionRepository;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseAccessService;
import io.github.uou_capstone.aiplatform.integration.fastapi.FastApiBridgeClient;
import io.github.uou_capstone.aiplatform.util.sse.SseStreamPolicy;
import io.github.uou_capstone.aiplatform.util.sse.SseStreamSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discussion AI Assistant 서비스 — FastAPI {@code /bridge/discussion_assistant_stream} 호출.
 *
 * <p>흐름:
 * <ol>
 *   <li>{@link CourseAccessService#loadCourseAsParticipant} 로 권한 검증 + Course 로드</li>
 *   <li>최근 5개 게시글을 컨텍스트로 수집</li>
 *   <li>FastAPI 호출 → NDJSON {@code Flux<String>}</li>
 *   <li>{@link SseStreamSupport#wrapNdjsonByType} 으로 SSE 변환</li>
 * </ol>
 *
 * <p>FastAPI가 자체 {@code done} 라인을 emit 하므로 {@code appendDoneOnComplete=false}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiscussionAssistantService {

    private static final int RECENT_DISCUSSION_LIMIT = 5;
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(120);

    private final CourseAccessService courseAccessService;
    private final DiscussionRepository discussionRepository;
    private final FastApiBridgeClient fastApiBridgeClient;
    private final ObjectMapper objectMapper;

    public Flux<ServerSentEvent<Map<String, Object>>> streamAssistant(Long courseId, DiscussionAssistantRequest req) {
        Course course = courseAccessService.loadCourseAsParticipant(courseId);
        List<Discussion> recent = discussionRepository.findTop5ByCourseOrderByCreatedAtDesc(course);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("courseId", course.getId());
        body.put("courseName", course.getTitle());
        body.put("topic", req.getTopic());
        body.put("category", req.getCategory() == null ? null : req.getCategory().name());
        body.put("previousDraft", req.getPreviousDraft());
        body.put("recentDiscussions", recent.stream()
                .limit(RECENT_DISCUSSION_LIMIT)
                .map(d -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("title", d.getTitle());
                    item.put("category", d.getCategory() == null ? null : d.getCategory().name());
                    return item;
                })
                .toList());

        log.info("Discussion assistant stream 시작: courseId={}, topic='{}'", courseId, req.getTopic());

        Flux<String> upstream = fastApiBridgeClient.discussionAssistantStream(body);
        SseStreamPolicy policy = SseStreamPolicy.builder()
                .idleTimeout(IDLE_TIMEOUT)
                .heartbeatPassthrough(true)
                .appendDoneOnComplete(false)
                .build();
        return SseStreamSupport.wrapNdjsonByType(upstream, objectMapper, policy, this::mapError);
    }

    private Map<String, Object> mapError(Throwable e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "error");
        m.put("code", "AI_SERVER_ERROR");
        if (e instanceof WebClientResponseException ex) {
            log.error("FastAPI discussion_assistant_stream 오류: status={}", ex.getStatusCode(), e);
            m.put("message", "AI 서비스 호출에 실패했습니다.");
        } else {
            log.error("FastAPI discussion_assistant_stream 알 수 없는 오류", e);
            m.put("message", "AI 서비스 호출 중 오류가 발생했습니다.");
        }
        return m;
    }
}
