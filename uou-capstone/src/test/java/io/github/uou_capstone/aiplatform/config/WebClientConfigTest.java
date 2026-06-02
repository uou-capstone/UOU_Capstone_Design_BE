package io.github.uou_capstone.aiplatform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WebClientConfigTest {

    @Test
    void aiServiceWebClientSerializesLocalDateTimeAsIsoString() {
        CapturedServer capturedServer = startCapturedServer();
        try {
            WebClient client = configFor(capturedServer.baseUrl()).aiServiceWebClient();

            client.post()
                    .uri("/test")
                    .bodyValue(Map.of("occurredAt", LocalDateTime.of(2026, 6, 1, 14, 5, 39, 568858000)))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(5));

            assertIsoDateBody(capturedServer.body());
        } finally {
            capturedServer.dispose();
        }
    }

    @Test
    void aiServiceStreamingWebClientSerializesLocalDateTimeAsIsoString() {
        CapturedServer capturedServer = startCapturedServer();
        try {
            WebClient client = configFor(capturedServer.baseUrl()).aiServiceStreamingWebClient();

            client.post()
                    .uri("/bridge/report/student_chat_stream")
                    .bodyValue(Map.of("occurredAt", LocalDateTime.of(2026, 6, 1, 14, 5, 39, 568858000)))
                    .retrieve()
                    .bodyToFlux(String.class)
                    .collectList()
                    .block(Duration.ofSeconds(5));

            assertIsoDateBody(capturedServer.body());
        } finally {
            capturedServer.dispose();
        }
    }

    private WebClientConfig configFor(String baseUrl) {
        ObjectMapper objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        WebClientConfig config = new WebClientConfig(new MockEnvironment(), objectMapper);
        ReflectionTestUtils.setField(config, "aiServiceBaseUrl", baseUrl);
        ReflectionTestUtils.setField(config, "aiSecretKey", "test-secret");
        return config;
    }

    private CapturedServer startCapturedServer() {
        AtomicReference<String> body = new AtomicReference<>();
        DisposableServer server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> request.receive()
                        .aggregate()
                        .asString()
                        .doOnNext(body::set)
                        .then(response.status(200).sendString(Mono.just("{}")).then()))
                .bindNow();
        return new CapturedServer(server, body);
    }

    private void assertIsoDateBody(String body) {
        assertThat(body).contains("\"occurredAt\":\"2026-06-01T14:05:39.568858\"");
        assertThat(body).doesNotContain("\"occurredAt\":[2026,6,1");
    }

    private record CapturedServer(DisposableServer server, AtomicReference<String> bodyRef) {
        String baseUrl() {
            return "http://" + server.host() + ":" + server.port();
        }

        public String body() {
            return bodyRef.get();
        }

        void dispose() {
            server.disposeNow();
        }
    }
}
