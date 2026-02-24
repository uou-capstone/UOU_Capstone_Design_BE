package io.github.uou_capstone.aiplatform.agent.base;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.agent.Agent;
import io.github.uou_capstone.aiplatform.agent.AgentRequest;
import io.github.uou_capstone.aiplatform.agent.StreamingEvent;
import io.github.uou_capstone.aiplatform.service.AgentPerformanceLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Abstract Agent
 * 모든 Agent의 기본 구현 클래스
 * FastAPI AI 서비스와의 통신을 담당
 */
@Slf4j
public abstract class AbstractAgent implements Agent {

    protected final WebClient aiServiceWebClient;
    protected final ObjectMapper objectMapper;
    protected final String agentName; // Agent 이름 (로깅용)
    protected final AgentPerformanceLogger performanceLogger; // 성능 로깅

    protected AbstractAgent(WebClient aiServiceWebClient, ObjectMapper objectMapper, 
                           AgentPerformanceLogger performanceLogger, String agentName) {
        this.aiServiceWebClient = aiServiceWebClient;
        this.objectMapper = objectMapper;
        this.performanceLogger = performanceLogger;
        this.agentName = agentName;
    }

    /**
     * FastAPI 엔드포인트 경로
     * 각 Agent는 자신의 엔드포인트를 반환해야 함
     */
    protected abstract String getEndpoint();

    /**
     * 요청 본문 생성
     * 각 Agent는 자신의 요청 형식에 맞게 구현
     */
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
                .bodyToMono(responseType)
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
                .uri(getEndpoint() + "/stream") // 스트리밍 엔드포인트
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
                                        // 429: 할당량 초과 (Rate Limit)
                                        if (body != null && body.contains("Please retry in")) {
                                            errorMessage += "API 할당량이 초과되었습니다. 잠시 후 자동으로 재시도합니다.";
                                        } else if (body != null && body.contains("quota")) {
                                            errorMessage += "API 할당량이 초과되었습니다. 잠시 후 자동으로 재시도합니다.";
                                        } else {
                                            errorMessage += "API 할당량이 초과되었습니다. 잠시 후 자동으로 재시도합니다.";
                                        }
                                    } else if (body != null && body.contains("high demand")) {
                                        errorMessage += "서비스가 일시적으로 사용할 수 없습니다. 잠시 후 자동으로 재시도합니다.";
                                    } else {
                                        errorMessage += "AI 서비스 통신 오류가 발생했습니다. 잠시 후 자동으로 재시도합니다.";
                                    }
                                    return new RuntimeException(errorMessage);
                                })
                                .switchIfEmpty(Mono.just(new RuntimeException(
                                    "AI 서비스 오류 (" + response.statusCode() + "): 서비스가 일시적으로 사용할 수 없습니다. 잠시 후 자동으로 재시도합니다.")))
                                .flatMap(Mono::error);
                    })
                .bodyToFlux(StreamingEvent.class)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2)) // 최대 3회 재시도, 2초 간격으로 시작
                        .maxBackoff(Duration.ofSeconds(10)) // 최대 10초 대기
                        .filter(throwable -> {
                            // 재시도 가능한 에러만 필터링
                            String message = throwable.getMessage();
                            if (message == null) {
                                return false;
                            }
                            // 503, 429 에러 또는 관련 메시지가 포함된 경우 재시도
                            return message.contains("503") || 
                                   message.contains("429") ||
                                   message.contains("RESOURCE_EXHAUSTED") ||
                                   message.contains("quota") ||
                                   message.contains("high demand") || 
                                   message.contains("일시적으로 사용할 수 없습니다") ||
                                   message.contains("할당량이 초과되었습니다") ||
                                   message.contains("Please retry");
                        })
                        .doBeforeRetry(retrySignal -> {
                            log.warn("[{}] 재시도 중... (시도 횟수: {}/{})", 
                                    agentName, retrySignal.totalRetries() + 1, 3);
                        })
                        .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> {
                            log.error("[{}] 재시도 실패: 최대 재시도 횟수 초과", agentName);
                            return retrySignal.failure();
                        }))
                .doOnError(error -> {
                    log.error("[{}] Agent streaming failed: {}", agentName, error.getMessage(), error);
                })
                .doOnComplete(() -> log.info("[{}] Agent streaming completed", agentName))
                .onErrorResume(error -> {
                    // 재시도 실패 또는 재시도 불가능한 에러 발생 시 에러 이벤트를 보내고 스트림 종료
                    String errorMessage = error.getMessage() != null ? error.getMessage() : "알 수 없는 오류";
                    
                    // 재시도 가능한 에러인지 확인
                    boolean isRetryable = errorMessage.contains("503") || 
                                         errorMessage.contains("429") ||
                                         errorMessage.contains("RESOURCE_EXHAUSTED") ||
                                         errorMessage.contains("quota") ||
                                         errorMessage.contains("high demand") || 
                                         errorMessage.contains("일시적으로 사용할 수 없습니다") ||
                                         errorMessage.contains("할당량이 초과되었습니다") ||
                                         errorMessage.contains("Please retry");
                    
                    if (!isRetryable) {
                        // 재시도 불가능한 에러는 즉시 종료
                        log.error("[{}] 재시도 불가능한 에러: {}", agentName, errorMessage);
                    }
                    
                    return Flux.just(StreamingEvent.builder()
                            .type("error")
                            .delta("스트리밍 중 오류가 발생했습니다: " + errorMessage)
                            .build())
                            .concatWith(Flux.empty()); // 스트림 종료
                });
    }
}
