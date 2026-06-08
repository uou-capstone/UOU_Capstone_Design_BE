package io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.entity.Enrollment;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.StudentAiReportContextResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.service.CourseStudentReportService;
import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.dto.StudentReportAnalysisRequest;
import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.dto.StudentReportAnalysisResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.repository.StudentReportAnalysisRepository;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseAccessService;
import io.github.uou_capstone.aiplatform.integration.fastapi.FastApiBridgeClient;
import io.github.uou_capstone.aiplatform.util.sse.SseEventNames;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class StudentReportAnalysisService {

    private static final Duration STREAM_IDLE_TIMEOUT = Duration.ofSeconds(240);
    private static final Set<String> ALLOWED_MODELS = Set.of(
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.0-flash",
            "gemini-1.5-flash"
    );

    private final CourseAccessService courseAccessService;
    private final CourseStudentReportService courseStudentReportService;
    private final EnrollmentRepository enrollmentRepository;
    private final StudentReportAnalysisRepository analysisRepository;
    private final StudentReportAnalysisPersister persister;
    private final FastApiBridgeClient fastApiBridgeClient;
    private final ObjectMapper objectMapper;

    public Optional<StudentReportAnalysisResponse> get(Long courseId, Long studentId) {
        Access access = validateAccess(courseId, studentId);
        return analysisRepository.findByCourseAndStudent(access.course(), access.enrollment().getStudent())
                .map(entity -> new StudentReportAnalysisResponse(entity, objectMapper));
    }

    public Map<String, Object> analyze(Long courseId, Long studentId, StudentReportAnalysisRequest request) {
        Map<String, Object> body = preparePayload(courseId, studentId, request);

        log.info("Student report analysis started: courseId={}, studentId={}", courseId, studentId);
        String raw = fastApiBridgeClient.reportStudentAnalyze(body);
        Map<String, Object> data;
        try {
            data = objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new BusinessException(CommonErrorCode.AI_SERVER_ERROR,
                    "Student report analysis response parsing failed.");
        }
        persister.upsert(courseId, studentId, data);
        return data;
    }

    public Flux<ServerSentEvent<Map<String, Object>>> analyzeStream(Long courseId,
                                                                     Long studentId,
                                                                     StudentReportAnalysisRequest request) {
        Map<String, Object> body = preparePayload(courseId, studentId, request);

        log.info("Student report analysis stream started: courseId={}, studentId={}", courseId, studentId);
        Flux<String> upstream = fastApiBridgeClient.reportStudentAnalyzeStream(body);
        SseStreamPolicy policy = SseStreamPolicy.builder()
                .idleTimeout(STREAM_IDLE_TIMEOUT)
                .heartbeatPassthrough(true)
                .appendDoneOnComplete(false)
                .build();
        return SseStreamSupport.wrapNdjsonByType(upstream, objectMapper, policy, this::mapError)
                .doOnNext(event -> persistIfDone(courseId, studentId, event.event(), event.data()));
    }

    @SuppressWarnings("unchecked")
    void persistIfDone(Long courseId, Long studentId, String eventName, Map<String, Object> payload) {
        if (!SseEventNames.DONE.equals(eventName) || payload == null) return;
        Object inner = payload.get("data");
        if (inner instanceof Map<?, ?> innerMap) {
            try {
                persister.upsert(courseId, studentId, (Map<String, Object>) innerMap);
            } catch (Exception ex) {
                log.error("Student report analysis stream UPSERT failed: courseId={}, studentId={}",
                        courseId, studentId, ex);
            }
        }
    }

    private Map<String, Object> preparePayload(Long courseId,
                                               Long studentId,
                                               StudentReportAnalysisRequest request) {
        StudentAiReportContextResponse context =
                courseStudentReportService.getStudentAiReportContext(courseId, studentId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("context", context);
        String model = sanitizeModel(request == null ? null : request.getModel());
        if (model != null) {
            body.put("model", model);
        }
        return body;
    }

    private String sanitizeModel(String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String normalized = requested.trim();
        return ALLOWED_MODELS.contains(normalized) ? normalized : null;
    }

    private Access validateAccess(Long courseId, Long studentId) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        Enrollment enrollment = enrollmentRepository.findByCourseIdAndStudentIdWithUser(courseId, studentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        return new Access(course, enrollment);
    }

    private Map<String, Object> mapError(Throwable e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "error");
        m.put("code", "AI_SERVER_ERROR");
        if (e instanceof WebClientResponseException ex) {
            log.error("FastAPI report/student/analyze stream error: status={}", ex.getStatusCode(), e);
            m.put("message", "AI service call failed.");
        } else {
            log.error("FastAPI report/student/analyze stream unexpected error", e);
            m.put("message", "An error occurred while calling the AI service.");
        }
        return m;
    }

    private record Access(Course course, Enrollment enrollment) {}
}
