package io.github.uou_capstone.aiplatform.integration.fastapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.domain.course.lecture.exception.StreamingApiException;
import io.github.uou_capstone.aiplatform.util.NdjsonLineFilters;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FastAPI v1 레거시 강의 흐름 호환 클라이언트.
 *
 * <p>기존에는 /api/delegator/dispatch(stage 기반)를 호출했으나,
 * FastAPI v2.8부터는 v1 강의 흐름이 /api/v2/lectures/generate-stream 으로 정리되어
 * Spring에서 stage 요청을 해당 단건 API로 변환하는 shim 역할을 수행한다.
 */
@Component
@RequiredArgsConstructor
public class FastApiDelegatorClient {

    private final WebClient aiServiceWebClient;
    private final ObjectMapper objectMapper;

    public Mono<Void> dispatchGenerateContentAsync(Object requestBody, String secretKey) {
        Map<String, Object> payload = toMap(requestBody);
        Map<String, Object> lectureReq = buildLectureGenerateRequest(payload);
        return aiServiceWebClient.post()
                .uri("/api/v2/lectures/generate-stream")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> applyCommonHeaders(headers, secretKey))
                .body(BodyInserters.fromValue(lectureReq))
                .retrieve()
                .bodyToFlux(String.class)
                .onErrorMap(e -> new StreamingApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "강의 생성 스트림 호출 실패: " + e.getMessage()))
                .then();
    }

    public Map<String, Object> dispatchStage(Map<String, Object> requestBody, String secretKey) {
        String stage = String.valueOf(requestBody.getOrDefault("stage", ""));
        Map<String, Object> payload = toMap(requestBody.get("payload"));

        return switch (stage) {
            case "initialize" -> buildInitializeResponse(payload);
            case "get_next_content" -> callLectureGenerate(payload, secretKey);
            case "get_session" -> buildSessionResponse(payload);
            case "answer_question" -> buildAnswerResponse(payload);
            case "cancel" -> buildCancelResponse(payload);
            default -> throw new StreamingApiException(HttpStatus.BAD_REQUEST, "지원하지 않는 legacy stage: " + stage);
        };
    }

    /**
     * FastAPI /api/v2/lectures/generate-stream 의 NDJSON 델타를 그대로 Flux 로 중계.
     * reduce 없이 청크 단위로 방출하므로 SSE 실시간 전달에 사용한다.
     */
    public Flux<String> streamLectureContent(Map<String, Object> payload, String secretKey) {
        Map<String, Object> lectureReq = buildLectureGenerateRequest(payload);
        return aiServiceWebClient.post()
                .uri("/api/v2/lectures/generate-stream")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> applyCommonHeaders(headers, secretKey))
                .body(BodyInserters.fromValue(lectureReq))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, cr -> cr.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new StreamingApiException(
                                cr.statusCode(), extractErrorMessage(body, cr.statusCode())))))
                .onStatus(HttpStatusCode::is5xxServerError, cr -> cr.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new StreamingApiException(
                                cr.statusCode(), extractErrorMessage(body, cr.statusCode())))))
                .bodyToFlux(String.class)
                .filter(line -> !line.isBlank())
                .filter(line -> !NdjsonLineFilters.isHeartbeatLine(objectMapper, line))
                .map(this::extractMainDelta)
                .filter(text -> text != null && !text.isBlank());
    }

    private Map<String, Object> callLectureGenerate(Map<String, Object> payload, String secretKey) {
        Map<String, Object> lectureReq = buildLectureGenerateRequest(payload);

        String mergedContent = aiServiceWebClient.post()
                .uri("/api/v2/lectures/generate-stream")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> applyCommonHeaders(headers, secretKey))
                .body(BodyInserters.fromValue(lectureReq))
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
                .bodyToFlux(String.class)
                .filter(line -> !line.isBlank())
                .filter(line -> !NdjsonLineFilters.isHeartbeatLine(objectMapper, line))
                .map(this::extractMainDelta)
                .filter(text -> text != null && !text.isBlank())
                .reduce(new StringBuilder(), StringBuilder::append)
                .map(StringBuilder::toString)
                .block();

        if (mergedContent == null || mergedContent.isBlank()) {
            throw new StreamingApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AI 서비스 응답이 비어 있습니다.");
        }

        Map<String, Object> mapped = new HashMap<>();
        mapped.put("status", "PROCESSING");
        mapped.put("lectureId", toLong(payload.get("lecture_id")));
        mapped.put("contentType", "SCRIPT");
        mapped.put("contentData", mergedContent);
        mapped.put("chapterTitle", "페이지 설명");
        mapped.put("hasMore", false);
        mapped.put("waitingForAnswer", false);
        return mapped;
    }

    private Map<String, Object> buildInitializeResponse(Map<String, Object> payload) {
        Long lectureId = toLong(payload.get("lecture_id"));
        Map<String, Object> chapter = new HashMap<>();
        chapter.put("title", "페이지 설명");
        chapter.put("startPage", 1);
        chapter.put("endPage", 1);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "INITIALIZED");
        response.put("lectureId", lectureId);
        response.put("totalChapters", 1);
        response.put("chapters", List.of(chapter));
        return response;
    }

    private Map<String, Object> buildSessionResponse(Map<String, Object> payload) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "ACTIVE");
        response.put("lectureId", toLong(payload.get("lecture_id")));
        response.put("serviceStatus", "ACTIVE_V2_LECTURES");
        return response;
    }

    private Map<String, Object> buildAnswerResponse(Map<String, Object> payload) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "PROCESSING");
        response.put("lectureId", toLong(payload.get("lecture_id")));
        response.put("aiQuestionId", payload.get("ai_question_id"));
        response.put("supplementary", "답변이 접수되었습니다. 다음 콘텐츠로 진행해 주세요.");
        response.put("canContinue", true);
        return response;
    }

    private Map<String, Object> buildCancelResponse(Map<String, Object> payload) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "CANCELED");
        response.put("lectureId", toLong(payload.get("lecture_id")));
        return response;
    }

    private Map<String, Object> buildLectureGenerateRequest(Map<String, Object> payload) {
        String pdfPath = stringValue(payload.get("pdf_path"));
        if (!StringUtils.hasText(pdfPath)) {
            throw new StreamingApiException(HttpStatus.BAD_REQUEST, "pdf_path가 필요합니다.");
        }
        Map<String, Object> req = new HashMap<>();
        req.put("page_number", 1);
        req.put("pdf_path", pdfPath);
        req.put("chapter_title", "페이지 설명");
        req.put("detail", "NORMAL");
        return req;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        if (value instanceof Map<?, ?> raw) {
            return (Map<String, Object>) raw;
        }
        return objectMapper.convertValue(value, new TypeReference<Map<String, Object>>() {});
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String extractMainDelta(String line) {
        try {
            JsonNode node = objectMapper.readTree(line);
            if ("error".equals(node.path("type").asText())) {
                String msg = node.path("message").asText("");
                throw new StreamingApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        StringUtils.hasText(msg) ? msg : "AI 스트림 처리 중 오류가 발생했습니다.");
            }
            if ("agent_delta".equals(node.path("type").asText())
                    && "main".equals(node.path("channel").asText())) {
                return node.path("delta").asText("");
            }
            return "";
        } catch (StreamingApiException e) {
            throw e;
        } catch (Exception e) {
            return "";
        }
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

