package io.github.uou_capstone.aiplatform.agent.base;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.agent.AgentRequest;
import io.github.uou_capstone.aiplatform.agent.exception.AgentExecutionException;
import io.github.uou_capstone.aiplatform.service.AgentPerformanceLogger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbstractAgentTest {

    @Test
    void execute_throwsAgentExecutionExceptionWhenFastApiResponseCannotBeParsed() {
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(
                        ClientResponse.create(HttpStatus.OK)
                                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                .body("not-json")
                                .build()))
                .build();

        TestAgent agent = new TestAgent(webClient, new ObjectMapper(), new AgentPerformanceLogger());

        assertThatThrownBy(() -> agent.execute(new TestAgentRequest("prompt"), TestResponse.class))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessage("Failed to parse FastAPI response");
    }

    private static final class TestAgent extends AbstractAgent {

        private TestAgent(WebClient aiServiceWebClient, ObjectMapper objectMapper,
                          AgentPerformanceLogger performanceLogger) {
            super(aiServiceWebClient, objectMapper, performanceLogger, "test-agent");
        }

        @Override
        protected String getEndpoint() {
            return "/test";
        }

        @Override
        protected Object buildRequestBody(AgentRequest request) {
            return Map.of("prompt", request.getPrompt());
        }
    }

    private record TestAgentRequest(String prompt) implements AgentRequest {
        @Override
        public String getPrompt() {
            return prompt;
        }

        @Override
        public Map<String, Object> getContext() {
            return Map.of();
        }
    }

    private record TestResponse(String value) {
    }
}
