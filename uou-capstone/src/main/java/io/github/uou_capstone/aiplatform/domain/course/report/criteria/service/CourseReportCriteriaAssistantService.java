package io.github.uou_capstone.aiplatform.domain.course.report.criteria.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.CriteriaAssistantChatRequest;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.CriteriaAssistantRequest;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.ReportCriterionResponse;
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
import java.util.stream.Stream;

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
    private final CourseReportCriteriaCatalog catalog;
    private final FastApiBridgeClient fastApiBridgeClient;
    private final ObjectMapper objectMapper;

    public Flux<ServerSentEvent<Map<String, Object>>> streamAssistant(Long courseId, CriteriaAssistantRequest req) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        List<CourseReportCriterion> existing = criterionRepository.findByCourseOrderByIdAsc(course);

        List<Map<String, Object>> existingDtos = Stream.concat(
                        catalog.builtInCriteria().stream().map(this::toAssistantCriterion),
                        existing.stream().map(this::toAssistantCriterion))
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

    public Flux<ServerSentEvent<Map<String, Object>>> streamChat(Long courseId, CriteriaAssistantChatRequest req) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        List<CourseReportCriterion> additional = criterionRepository.findByCourseOrderByIdAsc(course);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("courseId", course.getId());
        body.put("courseName", course.getTitle());
        body.put("builtInCriteria", catalog.builtInCriteria().stream()
                .map(this::toAssistantCriterion)
                .toList());
        body.put("additionalCriteria", additional.stream()
                .map(this::toAssistantCriterion)
                .toList());
        if (req.getMessage() != null) {
            body.put("message", req.getMessage());
        }
        if (req.getMessages() != null) {
            body.put("messages", req.getMessages());
        }
        if (req.getHistory() != null) {
            body.put("history", req.getHistory());
        }
        if (req.getCurrentProposal() != null) {
            body.put("currentProposal", req.getCurrentProposal());
        }
        body.put("model", req.getModel());
        if (req.getResponseJsonSchema() != null) {
            body.put("responseJsonSchema", req.getResponseJsonSchema());
        }

        log.info("Criteria assistant chat stream 시작: courseId={}, additionalCriteria={}, hasMessages={}, hasMessage={}",
                courseId, additional.size(),
                req.getMessages() != null && !req.getMessages().isEmpty(),
                req.getMessage() != null && !req.getMessage().isBlank());

        Flux<String> upstream = fastApiBridgeClient.reportCriteriaAssistantChatStream(body);
        SseStreamPolicy policy = SseStreamPolicy.builder()
                .idleTimeout(IDLE_TIMEOUT)
                .heartbeatPassthrough(true)
                .appendDoneOnComplete(false)
                .build();
        return SseStreamSupport.wrapNdjsonByType(upstream, objectMapper, policy, this::mapError);
    }

    private Map<String, Object> toAssistantCriterion(CourseReportCriterion c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getLabel());
        m.put("label", c.getLabel());
        m.put("description", c.getDescription());
        m.put("weight", c.getWeight());
        m.put("updatedAt", c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : null);
        m.put("builtIn", false);
        m.put("isBuiltIn", false);
        return m;
    }

    private Map<String, Object> toAssistantCriterion(ReportCriterionResponse c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("key", c.getKey());
        m.put("name", c.getLabel());
        m.put("label", c.getLabel());
        m.put("description", c.getDescription());
        m.put("weight", c.getWeight());
        m.put("builtIn", c.isBuiltIn());
        m.put("isBuiltIn", c.isBuiltIn());
        return m;
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
