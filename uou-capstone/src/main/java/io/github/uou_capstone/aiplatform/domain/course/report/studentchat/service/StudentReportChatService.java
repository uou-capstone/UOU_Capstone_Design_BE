package io.github.uou_capstone.aiplatform.domain.course.report.studentchat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.StudentReportDetailResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.StudentAiReportContextResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.service.CourseStudentReportService;
import io.github.uou_capstone.aiplatform.domain.course.report.studentchat.dto.StudentReportChatRequest;
import io.github.uou_capstone.aiplatform.domain.course.report.studentchat.entity.StudentReportChatSession;
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
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final StudentReportChatPersistenceService chatPersistenceService;
    private final FastApiBridgeClient fastApiBridgeClient;
    private final ObjectMapper objectMapper;

    public Flux<ServerSentEvent<Map<String, Object>>> streamChat(Long courseId,
                                                                  Long studentId,
                                                                  StudentReportChatRequest req) {
        StudentReportChatSession chatSession = chatPersistenceService
                .getOrCreateSession(courseId, studentId, req.getSessionId());
        chatPersistenceService.saveUserMessage(chatSession.getId(), extractUserMessage(req));

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
        StringBuilder assistantBuffer = new StringBuilder();
        AtomicBoolean saved = new AtomicBoolean(false);
        Flux<ServerSentEvent<Map<String, Object>>> events = SseStreamSupport
                .wrapNdjsonByType(upstream, objectMapper, policy, this::mapError)
                .doOnNext(event -> captureAssistantText(event, assistantBuffer))
                .doFinally(signalType -> {
                    if (saved.compareAndSet(false, true) && !assistantBuffer.isEmpty()) {
                        chatPersistenceService.saveAssistantMessage(chatSession.getId(), assistantBuffer.toString());
                    }
                });

        Map<String, Object> sessionPayload = new LinkedHashMap<>();
        sessionPayload.put("type", "session");
        sessionPayload.put("sessionId", chatSession.getId());
        return Flux.just(SseStreamSupport.sse("session", sessionPayload)).concatWith(events);
    }

    private String extractUserMessage(StudentReportChatRequest req) {
        if (req.getQuestion() != null && !req.getQuestion().isBlank()) {
            return req.getQuestion();
        }
        if (req.getMessages() == null || req.getMessages().isEmpty()) {
            return null;
        }
        for (int i = req.getMessages().size() - 1; i >= 0; i--) {
            Map<String, Object> message = req.getMessages().get(i);
            Object role = message.get("role");
            if (role != null && !"user".equalsIgnoreCase(String.valueOf(role))) {
                continue;
            }
            String content = firstString(message, "content", "message", "text");
            if (content != null && !content.isBlank()) {
                return content;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void captureAssistantText(ServerSentEvent<Map<String, Object>> event, StringBuilder buffer) {
        Map<String, Object> data = event.data();
        if (data == null || SseEventNames.ERROR.equals(event.event()) || SseEventNames.TIMEOUT.equals(event.event())) {
            return;
        }
        if (SseEventNames.DONE.equals(event.event()) && !buffer.isEmpty()) {
            return;
        }
        String text = firstString(data, "delta", "content", "message", "answer", "text");
        if (text == null && data.get("data") instanceof Map<?, ?> nested) {
            text = firstString((Map<String, Object>) nested, "answer", "content", "message", "text");
        }
        if (text != null && !text.isBlank()) {
            buffer.append(text);
        }
    }

    private String firstString(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
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
