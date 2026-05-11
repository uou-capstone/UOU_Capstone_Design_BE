package io.github.uou_capstone.aiplatform.domain.course.report.studentchat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.StudentReportDetailResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.StudentAiReportContextResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.service.CourseStudentReportService;
import io.github.uou_capstone.aiplatform.domain.course.report.studentchat.dto.StudentReportChatRequest;
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
import java.util.Map;

/**
 * Student Report Chatbot 서비스 — FastAPI {@code /bridge/report/student_chat_stream} 호출.
 *
 * <p>흐름:
 * <ol>
 *   <li>{@link CourseStudentReportService#getStudentAiReportContext} 로 context DTO 빌드
 *       (내부에서 교사 권한 검증)</li>
 *   <li>{@link CourseStudentReportService#getStudentReportDetail} 로 report DTO 빌드</li>
 *   <li>context + report + messages/question + model 을 FastAPI 에 forward</li>
 *   <li>{@link SseStreamSupport#wrapNdjsonByType} 으로 SSE 변환</li>
 * </ol>
 *
 * <p>FastAPI 가 자체 {@code done} 라인을 emit 하므로 {@code appendDoneOnComplete=false}.
 * 대화 이력은 저장하지 않음 — FE 가 {@code messages[]} 를 매 요청마다 그대로 전달.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentReportChatService {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(180);

    private final CourseStudentReportService courseStudentReportService;
    private final FastApiBridgeClient fastApiBridgeClient;
    private final ObjectMapper objectMapper;

    public Flux<ServerSentEvent<Map<String, Object>>> streamChat(Long courseId,
                                                                  Long studentId,
                                                                  StudentReportChatRequest req) {
        StudentAiReportContextResponse context = courseStudentReportService
                .getStudentAiReportContext(courseId, studentId);
        StudentReportDetailResponse report = courseStudentReportService
                .getStudentReportDetail(courseId, studentId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("context", context);
        body.put("report", report);
        if (req.getMessages() != null) {
            body.put("messages", req.getMessages());
        }
        if (req.getQuestion() != null) {
            body.put("question", req.getQuestion());
        }
        body.put("model", req.getModel());

        log.info("Student report chat stream 시작: courseId={}, studentId={}, hasMessages={}, hasQuestion={}",
                courseId, studentId,
                req.getMessages() != null && !req.getMessages().isEmpty(),
                req.getQuestion() != null && !req.getQuestion().isBlank());

        Flux<String> upstream = fastApiBridgeClient.studentReportChatStream(body);
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
            log.error("FastAPI report/student_chat_stream 오류: status={}", ex.getStatusCode(), e);
            m.put("message", "AI 서비스 호출에 실패했습니다.");
        } else {
            log.error("FastAPI report/student_chat_stream 알 수 없는 오류", e);
            m.put("message", "AI 서비스 호출 중 오류가 발생했습니다.");
        }
        return m;
    }
}
