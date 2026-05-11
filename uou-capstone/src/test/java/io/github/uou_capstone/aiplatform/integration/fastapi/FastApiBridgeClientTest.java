package io.github.uou_capstone.aiplatform.integration.fastapi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 신규 6 메서드의 정확한 path / method / WebClient 선택을 회귀 방지로 검증.
 * 모든 stream 메서드는 streaming WebClient, JSON 메서드는 일반 WebClient 를 써야 한다.
 */
class FastApiBridgeClientTest {

    private WebClient jsonClient;
    private WebClient streamingClient;
    private FastApiBridgeClient client;

    @BeforeEach
    void setUp() {
        jsonClient = mock(WebClient.class, RETURNS_DEEP_STUBS);
        streamingClient = mock(WebClient.class, RETURNS_DEEP_STUBS);

        when(jsonClient.post().uri(anyString()).bodyValue(any())
                .retrieve().bodyToMono(String.class))
                .thenReturn(Mono.just("{}"));
        when(streamingClient.post().uri(anyString()).bodyValue(any())
                .retrieve().bodyToFlux(String.class))
                .thenReturn(Flux.empty());

        client = new FastApiBridgeClient(jsonClient, streamingClient);
    }

    @Test
    @DisplayName("discussionAssistantStream → POST /bridge/discussion_assistant_stream (streaming)")
    void discussionAssistantStreamPath() {
        client.discussionAssistantStream(Map.of("topic", "t"));
        verify(streamingClient.post()).uri("/bridge/discussion_assistant_stream");
    }

    @Test
    @DisplayName("examStudioPdfContext → POST /bridge/exam_studio/pdf_context (json)")
    void examStudioPdfContextPath() {
        client.examStudioPdfContext(Map.of("pdfPath", "uploads/x.pdf"));
        verify(jsonClient.post()).uri("/bridge/exam_studio/pdf_context");
    }

    @Test
    @DisplayName("examStudioChatStream → POST /bridge/exam_studio/chat_stream (streaming)")
    void examStudioChatStreamPath() {
        client.examStudioChatStream(Map.of("contextId", "x"));
        verify(streamingClient.post()).uri("/bridge/exam_studio/chat_stream");
    }

    @Test
    @DisplayName("studentReportChatStream → POST /bridge/report/student_chat_stream (streaming)")
    void studentReportChatStreamPath() {
        client.studentReportChatStream(Map.of("question", "q"));
        verify(streamingClient.post()).uri("/bridge/report/student_chat_stream");
    }

    @Test
    @DisplayName("reportCriteriaAssistantStream → POST /bridge/report/criteria_assistant_stream (streaming)")
    void reportCriteriaAssistantStreamPath() {
        client.reportCriteriaAssistantStream(Map.of("desiredCount", 3));
        verify(streamingClient.post()).uri("/bridge/report/criteria_assistant_stream");
    }

    @Test
    @DisplayName("reportClassroomAnalyze → POST /bridge/report/classroom_analyze (json)")
    void reportClassroomAnalyzePath() {
        client.reportClassroomAnalyze(Map.of("courseId", 1));
        verify(jsonClient.post()).uri("/bridge/report/classroom_analyze");
    }

    @Test
    @DisplayName("reportClassroomAnalyzeStream → POST /bridge/report/classroom_analyze_stream (streaming)")
    void reportClassroomAnalyzeStreamPath() {
        client.reportClassroomAnalyzeStream(Map.of("courseId", 1));
        verify(streamingClient.post()).uri("/bridge/report/classroom_analyze_stream");
    }
}
