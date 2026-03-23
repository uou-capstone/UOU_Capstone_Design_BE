package io.github.uou_capstone.aiplatform.integration.fastapi;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Map;

/**
 * FastAPI v2 Lecture Note Generation API 클라이언트.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FastApiNoteGenClient {

    private final WebClient aiServiceWebClient;

    public Map<String, Object> startPhase3To5Auto(Map<String, Object> requestBody) {
        try {
            return aiServiceWebClient.post()
                    .uri("/api/v2/lecture-gen/phase3-5/auto")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(requestBody))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (WebClientResponseException wce) {
            String body;
            try {
                body = wce.getResponseBodyAsString();
            } catch (Exception ignored) {
                body = null;
            }
            String detail = (body != null && !body.isBlank()) ? body : wce.getMessage();
            String msg = "FastAPI /api/v2/lecture-gen/phase3-5/auto 호출 실패 (status="
                    + wce.getStatusCode().value() + "): " + detail;
            log.error(msg, wce);
            throw new BusinessException(CommonErrorCode.AI_SERVER_ERROR, msg);
        }
    }

    public Map<String, Object> getStatusByUrl(String statusUrl) {
        try {
            return aiServiceWebClient.get()
                    .uri(statusUrl)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (Exception e) {
            throw new BusinessException(CommonErrorCode.AI_SERVER_ERROR,
                    "FastAPI 상태 조회 실패: " + e.getMessage());
        }
    }
}

