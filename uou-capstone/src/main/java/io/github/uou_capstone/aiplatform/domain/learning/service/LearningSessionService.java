package io.github.uou_capstone.aiplatform.domain.learning.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.learning.dto.SessionEventRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 학습 세션 서비스
 *
 * FastAPI OrchestrationEngine의 학습 세션 API를 Spring Boot에서 프록시하는 역할.
 * 실제 세션 상태와 오케스트레이션 로직은 FastAPI가 담당하며,
 * Spring Boot는 인증/인가 처리 후 FastAPI로 요청을 위임한다.
 *
 * 프록시 대상:
 * - POST /api/learning/sessions/{lectureId}     → GET  FastAPI /api/session/by-lecture/{lectureId}
 * - POST /api/learning/sessions/{id}/event      → POST FastAPI /api/session/{id}/event  (NDJSON→SSE)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningSessionService {

    private final WebClient aiServiceWebClient;

    /**
     * 강의 ID로 학습 세션 조회 또는 신규 생성.
     *
     * FastAPI의 GET /api/session/by-lecture/{lectureId} 를 호출한다.
     * FastAPI가 세션 존재 여부를 확인하여 기존 세션을 반환하거나 새로 생성한다.
     *
     * @param lectureId 강의 ID
     * @return FastAPI 세션 응답 (sessionId, state, aiStatus 포함)
     */
    public Mono<Map<String, Object>> getOrCreateSession(Long lectureId) {
        log.info("학습 세션 조회/생성: lectureId={}", lectureId);

        return aiServiceWebClient.get()
                .uri("/api/session/by-lecture/{lectureId}", lectureId)
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> (Map<String, Object>) body)
                .onErrorMap(WebClientResponseException.class, e -> {
                    log.error("FastAPI 세션 생성 실패: lectureId={}, status={}, body={}",
                            lectureId, e.getStatusCode(), e.getResponseBodyAsString());
                    return new BusinessException(CommonErrorCode.AI_SERVER_ERROR,
                            "학습 세션 생성 중 AI 서비스 오류가 발생했습니다: " + e.getMessage());
                })
                .onErrorMap(Exception.class, e -> {
                    if (e instanceof BusinessException) return e;
                    log.error("FastAPI 세션 생성 중 알 수 없는 오류: lectureId={}", lectureId, e);
                    return new BusinessException(CommonErrorCode.AI_SERVER_ERROR,
                            "학습 세션 생성 중 오류가 발생했습니다.");
                });
    }

    /**
     * 학습 세션 이벤트 SSE 스트리밍 프록시.
     *
     * FastAPI의 POST /api/session/{sessionId}/event 를 호출하고
     * 반환되는 NDJSON 스트림을 SSE(text/event-stream)로 변환하여 클라이언트에 전달한다.
     *
     * FastAPI NDJSON 이벤트 포맷 예시:
     * {"type":"agent_delta","agent":"explainer","delta":"설명 텍스트..."}
     * {"type":"done","agent":"explainer","tool":"EXPLAIN_PAGE","final":true,"data":{}}
     * {"type":"error","message":"오류 메시지"}
     *
     * @param sessionId   학습 세션 ID (FastAPI 세션 식별자)
     * @param eventRequest 이벤트 요청 (type, payload 포함)
     * @return NDJSON 라인을 SSE data로 래핑한 Flux
     */
    public Flux<ServerSentEvent<String>> streamSessionEvent(String sessionId, SessionEventRequest eventRequest) {
        log.info("학습 세션 이벤트 스트림: sessionId={}, eventType={}", sessionId, eventRequest.getType());

        return aiServiceWebClient.post()
                .uri("/api/session/{sessionId}/event", sessionId)
                .bodyValue(eventRequest)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(line -> !line.isBlank())
                .map(line -> ServerSentEvent.<String>builder()
                        .event("message")
                        .data(line)
                        .build())
                .onErrorResume(WebClientResponseException.class, e -> {
                    log.error("FastAPI 이벤트 스트림 오류: sessionId={}, status={}", sessionId, e.getStatusCode());
                    String errorPayload = String.format(
                            "{\"type\":\"error\",\"message\":\"AI 서비스 오류(%s): %s\"}",
                            e.getStatusCode(), e.getMessage()
                    );
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("error")
                            .data(errorPayload)
                            .build());
                })
                .onErrorResume(Exception.class, e -> {
                    log.error("이벤트 스트림 중 알 수 없는 오류: sessionId={}", sessionId, e);
                    String errorPayload = String.format(
                            "{\"type\":\"error\",\"message\":\"%s\"}",
                            e.getMessage() != null ? e.getMessage().replace("\"", "'") : "알 수 없는 오류"
                    );
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("error")
                            .data(errorPayload)
                            .build());
                });
    }
}
