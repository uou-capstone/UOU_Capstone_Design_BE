package io.github.uou_capstone.aiplatform.agent.base;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.agent.Agent;
import io.github.uou_capstone.aiplatform.agent.AgentRequest;
import io.github.uou_capstone.aiplatform.agent.StreamingEvent;
import io.github.uou_capstone.aiplatform.agent.exception.AgentExecutionException;
import io.github.uou_capstone.aiplatform.service.AgentPerformanceLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

@Slf4j
public abstract class AbstractAgent implements Agent {

    protected final WebClient aiServiceWebClient;
    protected final ObjectMapper objectMapper;
    protected final String agentName;
    protected final AgentPerformanceLogger performanceLogger;

    protected AbstractAgent(WebClient aiServiceWebClient, ObjectMapper objectMapper,
                            AgentPerformanceLogger performanceLogger, String agentName) {
        this.aiServiceWebClient = aiServiceWebClient;
        this.objectMapper = objectMapper;
        this.performanceLogger = performanceLogger;
        this.agentName = agentName;
    }

    protected abstract String getEndpoint();

    protected abstract Object buildRequestBody(AgentRequest request);

    @Override
    public <T> T execute(AgentRequest request, Class<T> responseType) {
        log.info("[{}] Executing agent synchronously", agentName);
        java.time.LocalDateTime startTime = performanceLogger.startExecution(agentName);
        try {
            T result = executeAsync(request, responseType).block();
            performanceLogger.endExecution(agentName, startTime, true);
            return result;
        } catch (Exception e) {
            performanceLogger.endExecution(agentName, startTime, false);
            throw e;
        }
    }

    @Override
    public <T> Mono<T> executeAsync(AgentRequest request, Class<T> responseType) {
        log.info("[{}] Executing agent asynchronously", agentName);
        java.time.LocalDateTime startTime = performanceLogger.startExecution(agentName);

        Object requestBody = buildRequestBody(request);

        return aiServiceWebClient.post()
                .uri(getEndpoint())
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .doOnNext(rawResponse -> log.info("[{}] FastAPI Raw Response: {}", agentName, rawResponse))
                .map(rawResponse -> {
                    try {
                        T result = objectMapper.readValue(rawResponse, responseType);
                        log.info("[{}] Parsed Response: {}", agentName, objectMapper.writeValueAsString(result));
                        return result;
                    } catch (Exception e) {
                        log.error("[{}] Failed to parse response: {}", agentName, e.getMessage(), e);
                        throw new AgentExecutionException("Failed to parse FastAPI response", e);
                    }
                })
                .doOnError(error -> {
                    log.error("[{}] Agent execution failed", agentName, error);
                    performanceLogger.endExecution(agentName, startTime, false);
                })
                .doOnSuccess(result -> {
                    log.info("[{}] Agent execution completed", agentName);
                    performanceLogger.endExecution(agentName, startTime, true);
                });
    }

    @Override
    public Flux<StreamingEvent> executeStreaming(AgentRequest request) {
        log.info("[{}] Executing agent with streaming", agentName);

        Object requestBody = buildRequestBody(request);

        return aiServiceWebClient.post()
                .uri(getEndpoint() + "/stream")
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(status -> status.is5xxServerError() || status.value() == 503 || status.value() == 429,
                        response -> {
                            log.error("[{}] AI service returned error status: {}", agentName, response.statusCode());
                            return response.bodyToMono(String.class)
                                    .map(body -> {
                                        log.error("[{}] Error response body: {}", agentName, body);
                                        String errorMessage = "AI 서비스 오류 (" + response.statusCode() + "): ";

                                        if (response.statusCode().value() == 429) {
                                            errorMessage += "API 할당량이 초과되었습니다. 잠시 후 자동으로 재시도합니다.";
                                        } else if (body != null && body.contains("high demand")) {
                                            errorMessage += "서비스가 일시적으로 사용할 수 없습니다. 잠시 후 자동으로 재시도합니다.";
                                        } else {
                                            errorMessage += "AI 서비스 통신 오류가 발생했습니다. 잠시 후 자동으로 재시도합니다.";
                                        }

                                        return new AgentExecutionException(errorMessage);
                                    })
                                    .switchIfEmpty(Mono.just(new AgentExecutionException(
                                            "AI 서비스 오류 (" + response.statusCode()
                                                    + "): 서비스가 일시적으로 사용할 수 없습니다. 잠시 후 자동으로 재시도합니다.")))
                                    .flatMap(Mono::error);
                        })
                .bodyToFlux(StreamingEvent.class)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                        .maxBackoff(Duration.ofSeconds(10))
                        .filter(throwable -> {
                            String message = throwable.getMessage();
                            if (message == null) {
                                return false;
                            }
                            return message.contains("503")
                                    || message.contains("429")
                                    || message.contains("RESOURCE_EXHAUSTED")
                                    || message.contains("quota")
                                    || message.contains("high demand")
                                    || message.contains("일시적으로 사용할 수 없습니다")
                                    || message.contains("할당량이 초과되었습니다")
                                    || message.contains("Please retry");
                        })
                        .doBeforeRetry(retrySignal -> log.warn("[{}] 재시도 중... (시도 횟수: {}/{})",
                                agentName, retrySignal.totalRetries() + 1, 3))
                        .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> {
                            log.error("[{}] 재시도 실패: 최대 재시도 횟수 초과", agentName);
                            return retrySignal.failure();
                        }))
                .doOnError(error -> log.error("[{}] Agent streaming failed: {}", agentName, error.getMessage(), error))
                .doOnComplete(() -> log.info("[{}] Agent streaming completed", agentName))
                .onErrorResume(error -> {
                    String errorMessage = error.getMessage() != null ? error.getMessage() : "알 수 없는 오류";

                    boolean isRetryable = errorMessage.contains("503")
                            || errorMessage.contains("429")
                            || errorMessage.contains("RESOURCE_EXHAUSTED")
                            || errorMessage.contains("quota")
                            || errorMessage.contains("high demand")
                            || errorMessage.contains("일시적으로 사용할 수 없습니다")
                            || errorMessage.contains("할당량이 초과되었습니다")
                            || errorMessage.contains("Please retry");

                    if (!isRetryable) {
                        log.error("[{}] 재시도 불가능한 에러: {}", agentName, errorMessage);
                    }

                    return Flux.just(StreamingEvent.builder()
                                    .type("error")
                                    .delta("스트리밍 중 오류가 발생했습니다: " + errorMessage)
                                    .build())
                            .concatWith(Flux.empty());
                });
    }
}
