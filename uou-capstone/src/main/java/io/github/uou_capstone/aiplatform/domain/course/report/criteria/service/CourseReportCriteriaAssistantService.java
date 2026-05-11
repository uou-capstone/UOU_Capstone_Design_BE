package io.github.uou_capstone.aiplatform.domain.course.report.criteria.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.CriteriaAssistantRequest;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.entity.CourseReportCriterion;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.repository.CourseReportCriterionRepository;
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
 * Report Criteria AI Assistant — FastAPI {@code /bridge/report/criteria_assistant_stream} 호출.
 *
 * <p>중간 이벤트 {@code criterion_suggestion} 을 SSE 로 그대로 forward.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourseReportCriteriaAssistantService {

    private static final int DEFAULT_DESIRED_COUNT = 3;
    private static final String DEFAULT_LANGUAGE = "ko";
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(120);

    private final CourseAccessService courseAccessService;
    private final CourseReportCriterionRepository criterionRepository;
    private final FastApiBridgeClient fastApiBridgeClient;
    private final ObjectMapper objectMapper;

    public Flux<ServerSentEvent<Map<String, Object>>> streamAssistant(Long courseId, CriteriaAssistantRequest req) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        List<CourseReportCriterion> existing = criterionRepository.findByCourseOrderByIdAsc(course);

        List<Map<String, Object>> existingDtos = existing.stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("label", c.getLabel());
                    m.put("description", c.getDescription());
                    m.put("weight", c.getWeight());
                    return m;
                })
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("courseId", course.getId());
        body.put("courseName", course.getTitle());
        body.put("existingCriteria", existingDtos);
        body.put("desiredCount",
                req != null && req.getDesiredCount() != null ? req.getDesiredCount() : DEFAULT_DESIRED_COUNT);
        body.put("language",
                req != null && req.getLanguage() != null && !req.getLanguage().isBlank()
                        ? req.getLanguage()
                        : DEFAULT_LANGUAGE);

        log.info("Criteria assistant stream 시작: courseId={}, existing={}, desiredCount={}",
                courseId, existing.size(), body.get("desiredCount"));

        Flux<String> upstream = fastApiBridgeClient.reportCriteriaAssistantStream(body);
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
            log.error("FastAPI report/criteria_assistant_stream 오류: status={}", ex.getStatusCode(), e);
            m.put("message", "AI 서비스 호출에 실패했습니다.");
        } else {
            log.error("FastAPI report/criteria_assistant_stream 알 수 없는 오류", e);
            m.put("message", "AI 서비스 호출 중 오류가 발생했습니다.");
        }
        return m;
    }
}
