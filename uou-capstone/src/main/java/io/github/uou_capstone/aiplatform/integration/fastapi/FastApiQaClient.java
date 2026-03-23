package io.github.uou_capstone.aiplatform.integration.fastapi;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * FastAPI v2 QA 평가 API 클라이언트.
 */
@Component
@RequiredArgsConstructor
public class FastApiQaClient {

    private final WebClient aiServiceWebClient;

    public Map<String, Object> evaluate(Map<String, Object> qaRequest) {
        Map<String, Object> response = aiServiceWebClient.post()
                .uri("/api/v2/qa/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .header("ngrok-skip-browser-warning", "true")
                .body(BodyInserters.fromValue(qaRequest))
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (response == null || response.isEmpty()) {
            throw new BusinessException(CommonErrorCode.AI_CONTENT_GENERATION_FAILED);
        }
        return response;
    }
}

