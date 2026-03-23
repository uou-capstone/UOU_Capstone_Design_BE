package io.github.uou_capstone.aiplatform.integration.fastapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.domain.course.lecture.exception.StreamingApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * FastAPI legacy delegator API 전용 클라이언트.
 * v1 흐름 호환을 위해 유지하며 점진적으로 제거 예정.
 */
@Component
@RequiredArgsConstructor
public class FastApiDelegatorClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient aiServiceWebClient;
    private final ObjectMapper objectMapper;

    public Mono<Void> dispatchGenerateContentAsync(Object requestBody, String secretKey) {
        return aiServiceWebClient.post()
                .uri("/api/delegator/dispatch")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> applyCommonHeaders(headers, secretKey))
                .body(BodyInserters.fromValue(requestBody))
                .retrieve()
                .toBodilessEntity()
                .then();
    }

    public Map<String, Object> dispatchStage(Map<String, Object> requestBody, String secretKey) {
        Map<String, Object> response = aiServiceWebClient.post()
                .uri("/api/delegator/dispatch")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> applyCommonHeaders(headers, secretKey))
                .body(BodyInserters.fromValue(requestBody))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, clientResponse -> clientResponse.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new StreamingApiException(
                                clientResponse.statusCode(),
                                extractErrorMessage(body, clientResponse.statusCode())))))
                .onStatus(HttpStatusCode::is5xxServerError, clientResponse -> clientResponse.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new StreamingApiException(
                                clientResponse.statusCode(),
                                extractErrorMessage(body, clientResponse.statusCode())))))
                .bodyToMono(MAP_TYPE)
                .block();

        if (response == null) {
            throw new StreamingApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AI 서비스 응답이 비어 있습니다.");
        }
        return response;
    }

    private void applyCommonHeaders(HttpHeaders headers, String secretKey) {
        headers.set("ngrok-skip-browser-warning", "true");
        if (StringUtils.hasText(secretKey)) {
            headers.set("X-AI-SECRET-KEY", secretKey);
        }
    }

    private String extractErrorMessage(String body, HttpStatusCode statusCode) {
        if (!StringUtils.hasText(body)) {
            return "AI 서비스 호출 중 오류가 발생했습니다. (status=" + statusCode.value() + ")";
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node.has("detail")) {
                JsonNode detailNode = node.get("detail");
                return detailNode.isTextual() ? detailNode.asText() : detailNode.toString();
            }
            if (node.has("message") && node.get("message").isTextual()) {
                return node.get("message").asText();
            }
        } catch (Exception ignored) {
            // fall through and return raw body
        }
        return body;
    }
}

