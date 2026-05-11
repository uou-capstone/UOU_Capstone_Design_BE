package io.github.uou_capstone.aiplatform.domain.course.report.studentchat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.StudentReportDetailResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.StudentAiReportContextResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.service.CourseStudentReportService;
import io.github.uou_capstone.aiplatform.domain.course.report.studentchat.dto.StudentReportChatRequest;
import io.github.uou_capstone.aiplatform.integration.fastapi.FastApiBridgeClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FastAPI 로 보내는 body shape 회귀 방지.
 * context + report + question/messages + model 이 정확히 들어가는지 검증.
 */
@ExtendWith(MockitoExtension.class)
class StudentReportChatServiceTest {

    @Mock private CourseStudentReportService courseStudentReportService;
    @Mock private FastApiBridgeClient fastApiBridgeClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private StudentReportChatService service;

    @BeforeEach
    void setUp() {
        service = new StudentReportChatService(courseStudentReportService, fastApiBridgeClient, objectMapper);
    }

    @Test
    @DisplayName("question 단발 경로 — body 에 context + report + question 포함, messages 미포함")
    void questionPath_bodyShape() {
        StudentAiReportContextResponse ctx = StudentAiReportContextResponse.builder().build();
        StudentReportDetailResponse report = StudentReportDetailResponse.builder().build();
        when(courseStudentReportService.getStudentAiReportContext(1L, 2L)).thenReturn(ctx);
        when(courseStudentReportService.getStudentReportDetail(1L, 2L)).thenReturn(report);
        when(fastApiBridgeClient.studentReportChatStream(any())).thenReturn(Flux.empty());

        StudentReportChatRequest req = new StudentReportChatRequest();
        req.setQuestion("이 학생 약점이 뭐야?");

        service.streamChat(1L, 2L, req);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fastApiBridgeClient).studentReportChatStream(bodyCaptor.capture());
        Map<String, Object> body = bodyCaptor.getValue();

        assertThat(body).containsEntry("context", ctx);
        assertThat(body).containsEntry("report", report);
        assertThat(body).containsEntry("question", "이 학생 약점이 뭐야?");
        assertThat(body).doesNotContainKey("messages");
        assertThat(body).containsKey("model");
    }

    @Test
    @DisplayName("messages 이력 경로 — body 에 messages 포함, question 미포함")
    void messagesPath_bodyShape() {
        when(courseStudentReportService.getStudentAiReportContext(anyLong(), anyLong())).thenReturn(null);
        when(courseStudentReportService.getStudentReportDetail(anyLong(), anyLong())).thenReturn(null);
        when(fastApiBridgeClient.studentReportChatStream(any())).thenReturn(Flux.empty());

        StudentReportChatRequest req = new StudentReportChatRequest();
        List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", "hi"));
        req.setMessages(messages);

        service.streamChat(1L, 2L, req);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fastApiBridgeClient).studentReportChatStream(bodyCaptor.capture());
        Map<String, Object> body = bodyCaptor.getValue();

        assertThat(body).containsEntry("messages", messages);
        assertThat(body).doesNotContainKey("question");
    }

    @Test
    @DisplayName("question 과 messages 양쪽 — 둘 다 body 에 포함 (FastAPI 측이 우선순위 결정)")
    void bothPaths_bothIncluded() {
        when(courseStudentReportService.getStudentAiReportContext(anyLong(), anyLong())).thenReturn(null);
        when(courseStudentReportService.getStudentReportDetail(anyLong(), anyLong())).thenReturn(null);
        when(fastApiBridgeClient.studentReportChatStream(any())).thenReturn(Flux.empty());

        StudentReportChatRequest req = new StudentReportChatRequest();
        req.setQuestion("q");
        req.setMessages(List.of(Map.of("role", "user", "content", "m")));
        req.setModel("gemini-flash");

        service.streamChat(1L, 2L, req);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fastApiBridgeClient).studentReportChatStream(bodyCaptor.capture());
        Map<String, Object> body = bodyCaptor.getValue();

        assertThat(body).containsEntry("question", "q");
        assertThat(body).containsKey("messages");
        assertThat(body).containsEntry("model", "gemini-flash");
    }
}
