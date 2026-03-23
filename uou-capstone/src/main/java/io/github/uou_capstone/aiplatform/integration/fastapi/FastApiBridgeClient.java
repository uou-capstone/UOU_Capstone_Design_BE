package io.github.uou_capstone.aiplatform.integration.fastapi;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.util.BridgeResponseLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * FastAPI v3 Bridge API 전용 클라이언트.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FastApiBridgeClient {

    private final WebClient aiServiceWebClient;

    public String quizResult(Map<String, Object> body) {
        String raw = aiServiceWebClient.post()
                .uri("/api/v3/bridge/quiz/result")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .onErrorMap(e -> new BusinessException(CommonErrorCode.AI_SERVER_ERROR,
                        "퀴즈 생성 서비스 호출 실패: " + e.getMessage()))
                .block();
        BridgeResponseLogger.debugBody(log, "POST /api/v3/bridge/quiz/result", raw);
        return raw;
    }

    public String gradeResult(Map<String, Object> body) {
        String raw = aiServiceWebClient.post()
                .uri("/api/v3/bridge/grade/result")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .onErrorMap(e -> new BusinessException(CommonErrorCode.AI_SERVER_ERROR,
                        "채점 서비스 호출 실패: " + e.getMessage()))
                .block();
        BridgeResponseLogger.debugBody(log, "POST /api/v3/bridge/grade/result", raw);
        return raw;
    }

    public Flux<String> streamQuiz(Map<String, Object> body) {
        return aiServiceWebClient.post()
                .uri("/api/v3/bridge/quiz")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class);
    }
}

