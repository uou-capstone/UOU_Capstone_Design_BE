package io.github.uou_capstone.aiplatform.domain.course.notice.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.notice.assistant.dto.NoticeAssistantRequest;
import io.github.uou_capstone.aiplatform.domain.course.notice.entity.Notice;
import io.github.uou_capstone.aiplatform.domain.course.notice.repository.NoticeRepository;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class NoticeAssistantService {

    private static final int RECENT_NOTICE_LIMIT = 5;
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(120);

    private final CourseAccessService courseAccessService;
    private final NoticeRepository noticeRepository;
    private final FastApiBridgeClient fastApiBridgeClient;
    private final ObjectMapper objectMapper;

    public Flux<ServerSentEvent<Map<String, Object>>> streamAssistant(Long courseId, NoticeAssistantRequest req) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        List<Notice> recent = noticeRepository.findTop5ByCourseOrderByCreatedAtDesc(course);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("courseId", course.getId());
        body.put("courseName", course.getTitle());
        body.put("topic", req.getTopic());
        body.put("category", req.getCategory() == null ? null : req.getCategory().name());
        body.put("priority", req.getPriority() == null ? null : req.getPriority().name());
        body.put("previousDraft", req.getPreviousDraft());
        body.put("recentNotices", recent.stream()
                .limit(RECENT_NOTICE_LIMIT)
                .map(n -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("title", n.getTitle());
                    item.put("category", n.getCategory() == null ? null : n.getCategory().name());
                    item.put("priority", n.getPriority() == null ? null : n.getPriority().name());
                    return item;
                })
                .toList());

        log.info("Notice assistant stream started: courseId={}, topic='{}'", courseId, req.getTopic());

        Flux<String> upstream = fastApiBridgeClient.noticeAssistantStream(body);
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
            log.error("FastAPI notice_assistant_stream error: status={}", ex.getStatusCode(), e);
            m.put("message", "AI 서비스 호출에 실패했습니다.");
        } else {
            log.error("FastAPI notice_assistant_stream unknown error", e);
            m.put("message", "AI 서비스 호출 중 오류가 발생했습니다.");
        }
        return m;
    }
}
