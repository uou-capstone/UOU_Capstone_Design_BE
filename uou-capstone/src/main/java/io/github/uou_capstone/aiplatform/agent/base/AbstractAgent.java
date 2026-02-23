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
                .bodyToFlux(StreamingEvent.class)
                .doOnError(error -> log.error("[{}] Agent streaming failed", agentName, error))
                .doOnComplete(() -> log.info("[{}] Agent streaming completed", agentName));
    }
}
