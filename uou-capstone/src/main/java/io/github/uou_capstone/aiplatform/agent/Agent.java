package io.github.uou_capstone.aiplatform.agent;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Agent 인터페이스
 * 모든 AI Agent가 구현해야 하는 기본 인터페이스
 */
public interface Agent {
    /**
     * Agent 실행 (동기)
     * @param request Agent 요청
     * @param responseType 응답 타입
     * @return Agent 실행 결과
     */
    <T> T execute(AgentRequest request, Class<T> responseType);

    /**
     * Agent 실행 (비동기)
     * @param request Agent 요청
     * @param responseType 응답 타입
     * @return Agent 실행 결과 Mono
     */
    <T> Mono<T> executeAsync(AgentRequest request, Class<T> responseType);

    /**
     * Agent 실행 (스트리밍)
     * @param request Agent 요청
     * @return 스트리밍 이벤트 Flux
     */
    Flux<StreamingEvent> executeStreaming(AgentRequest request);
}
