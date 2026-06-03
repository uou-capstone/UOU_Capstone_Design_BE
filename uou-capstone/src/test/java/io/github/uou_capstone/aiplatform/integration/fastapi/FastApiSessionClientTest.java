package io.github.uou_capstone.aiplatform.integration.fastapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FastApiSessionClientTest {

    @Test
    void invalidateByLecture_callsDeleteSessionEndpointWithLectureId() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    capturedRequest.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build());
                })
                .build();
        FastApiSessionClient client = new FastApiSessionClient(webClient, webClient, new ObjectMapper());

        client.invalidateByLecture(100L).block();

        assertThat(capturedRequest.get().method()).isEqualTo(HttpMethod.DELETE);
        assertThat(capturedRequest.get().url().getPath()).isEqualTo("/api/v3/session/100");
    }
}
