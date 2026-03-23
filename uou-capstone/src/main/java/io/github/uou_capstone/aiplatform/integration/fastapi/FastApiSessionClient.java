package io.github.uou_capstone.aiplatform.integration.fastapi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * FastAPI v3 Session API 전용 클라이언트.
 * 도메인 서비스에서 WebClient 디테일을 숨긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FastApiSessionClient {

    private final WebClient aiServiceWebClient;
    private final ObjectMapper objectMapper;

    public Mono<Map<String, Object>> getOrCreateByLecture(Long lectureId, String pdfPath) {
        return aiServiceWebClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/api/v3/session/by-lecture/{lectureId}");
                    if (StringUtils.hasText(pdfPath)) {
                        builder.queryParam("pdf_path", pdfPath);
                    }
                    return builder.build(lectureId);
                })
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> (Map<String, Object>) body)
                .onErrorMap(WebClientResponseException.class, e -> {
                    log.error("FastAPI session by-lecture 호출 실패: lectureId={}, status={}, body={}",
                            lectureId, e.getStatusCode(), e.getResponseBodyAsString());
                    return new BusinessException(CommonErrorCode.AI_SERVER_ERROR,
                            "학습 세션 생성 중 AI 서비스 오류가 발생했습니다: " + e.getMessage());
                });
    }

    public Flux<String> streamEvent(Long sessionId, Map<String, Object> eventBody) {
        return aiServiceWebClient.post()
                .uri("/api/v3/session/{sessionId}/event/stream", sessionId)
                .bodyValue(eventBody)
                .retrieve()
                .bodyToFlux(String.class);
    }

    public Map<String, Object> callEvent(String sessionId, Map<String, Object> eventBody) {
        String raw = aiServiceWebClient.post()
                .uri("/api/v3/session/{sessionId}/event", sessionId)
                .bodyValue(eventBody)
                .retrieve()
                .bodyToMono(String.class)
                .onErrorMap(e -> new BusinessException(CommonErrorCode.AI_SERVER_ERROR,
                        "세션 이벤트 호출 실패: " + e.getMessage()))
                .block();

        if (raw == null || raw.isBlank()) {
            throw new BusinessException(CommonErrorCode.AI_SERVER_ERROR, "세션 이벤트 응답이 비어 있습니다.");
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new BusinessException(CommonErrorCode.AI_SERVER_ERROR, "세션 이벤트 응답 파싱에 실패했습니다.");
        }
    }
}

