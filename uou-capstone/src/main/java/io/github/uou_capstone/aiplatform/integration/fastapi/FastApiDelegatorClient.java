package io.github.uou_capstone.aiplatform.integration.fastapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.domain.course.lecture.exception.StreamingApiException;
import io.github.uou_capstone.aiplatform.util.NdjsonLineFilters;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * FastAPI v1 레거시 강의 흐름 호환 클라이언트.
 *
 * <p>기존에는 /api/delegator/dispatch(stage 기반)를 호출했으나,
 * FastAPI v2.8부터는 v1 강의 흐름이 /api/v2/lectures/generate-stream 으로 정리되어
 * Spring에서 stage 요청을 해당 단건 API로 변환하는 shim 역할을 수행한다.
 */
@Component
public class FastApiDelegatorClient {

    /** 집계 응답용 (bodyToMono) */
    private final WebClient aiServiceWebClient;
    /** SSE/NDJSON 스트리밍 전용 (bodyToFlux) — HTTP/1.1 강제, 버퍼 256KB */
    private final WebClient aiServiceStreamingWebClient;
    private final ObjectMapper objectMapper;

    public FastApiDelegatorClient(WebClient aiServiceWebClient,
                                   WebClient aiServiceStreamingWebClient,
                                   ObjectMapper objectMapper) {
        this.aiServiceWebClient = aiServiceWebClient;
        this.aiServiceStreamingWebClient = aiServiceStreamingWebClient;
        this.objectMapper = objectMapper;
    }

    public Mono<Void> dispatchGenerateContentAsync(Object requestBody, String secretKey) {
        Map<String, Object> payload = toMap(requestBody);
        Map<String, Object> lectureReq = buildLectureGenerateRequest(payload);
        return aiServiceStreamingWebClient.post()
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
     * FastAPI /api/v2/lectures/generate-stream NDJSON 한 줄마다 {@link LectureStreamChunk} 로 분류.
     * 사고(thinking)와 본문(main)을 분리해 Legacy SSE에서 {@code event: thought} / {@code event: message} 로 보낸다.
     */
    public Flux<LectureStreamChunk> streamLectureContent(Map<String, Object> payload, String secretKey) {
        Map<String, Object> lectureReq = buildLectureGenerateRequest(payload);
        return aiServiceStreamingWebClient.post()
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
                .map(this::parseLectureStreamLine)
                .filter(Objects::nonNull);
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
                .map(this::parseLectureStreamLine)
                .filter(Objects::nonNull)
                .filter(c -> c.kind() == LectureStreamChunk.Kind.MAIN)
                .map(LectureStreamChunk::delta)
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
        Integer pageNumber = toInteger(payload.get("page_number"));
        if (pageNumber == null) {
            pageNumber = toInteger(payload.get("pageNumber"));
        }
        if (pageNumber == null || pageNumber <= 0) {
            pageNumber = 1;
        }
        String chapterTitle = stringValue(payload.get("chapter_title"));
        if (!StringUtils.hasText(chapterTitle)) {
            chapterTitle = stringValue(payload.get("chapterTitle"));
        }
        if (!StringUtils.hasText(chapterTitle)) {
            chapterTitle = "페이지 설명";
        }
        String detail = stringValue(payload.get("detail"));
        if (!StringUtils.hasText(detail)) {
            detail = "NORMAL";
        }
        String userMessage = stringValue(payload.get("user_message"));
        if (!StringUtils.hasText(userMessage)) {
            userMessage = stringValue(payload.get("userMessage"));
        }
        Map<String, Object> req = new HashMap<>();
        req.put("page_number", pageNumber);
        req.put("pdf_path", pdfPath);
        req.put("chapter_title", chapterTitle);
        req.put("detail", detail);
        if (StringUtils.hasText(userMessage)) {
            req.put("user_message", userMessage);
        }
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

    private Integer toInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static final Set<String> THOUGHT_CHANNEL_ALIASES = Set.of(
            "thinking", "internal", "thought", "reasoning", "thought_summary",
            "think", "reasoning_summary", "reasoning_delta", "thought_delta", "thinking_delta"
    );

    /**
     * NDJSON 한 줄 → 사고(THOUGHT) vs 본문(MAIN). FastAPI/Gemini 스트림 계약과 FE SSE( thought / message )에 맞춘다.
     */
    private LectureStreamChunk parseLectureStreamLine(String line) {
        try {
            JsonNode node = objectMapper.readTree(line);
            String type = node.path("type").asText("");
            if ("error".equals(type)) {
                String msg = node.path("message").asText("");
                throw new StreamingApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        StringUtils.hasText(msg) ? msg : "AI 스트림 처리 중 오류가 발생했습니다.");
            }

            // 명시적 사고 전용 타입
            if ("thought_delta".equals(type) || "thinking_delta".equals(type) || "reasoning_delta".equals(type)) {
                String d = extractDeltaText(node);
                return StringUtils.hasText(d)
                        ? new LectureStreamChunk(LectureStreamChunk.Kind.THOUGHT, d)
                        : null;
            }

            if ("agent_delta".equals(type)) {
                String channel = node.path("channel").asText("");
                String agent = node.path("agent").asText("");
                String channelOrAgent = StringUtils.hasText(channel) ? channel : agent;
                String delta = node.path("delta").asText("");
                if (!StringUtils.hasText(delta)) {
                    return null;
                }
                if (isThoughtChannelOrAgent(channelOrAgent)) {
                    return new LectureStreamChunk(LectureStreamChunk.Kind.THOUGHT, delta);
                }
                return new LectureStreamChunk(LectureStreamChunk.Kind.MAIN, delta);
            }

            String phase = node.path("phase").asText("");
            String role = node.path("role").asText("");
            if (isThoughtPhaseOrRole(phase) || isThoughtPhaseOrRole(role)) {
                String d = extractDeltaText(node);
                return StringUtils.hasText(d)
                        ? new LectureStreamChunk(LectureStreamChunk.Kind.THOUGHT, d)
                        : null;
            }

            String contentType = node.path("contentType").asText("");
            if ("THOUGHT".equalsIgnoreCase(contentType)) {
                String d = extractDeltaText(node);
                return StringUtils.hasText(d)
                        ? new LectureStreamChunk(LectureStreamChunk.Kind.THOUGHT, d)
                        : null;
            }

            return null;
        } catch (StreamingApiException e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isThoughtChannelOrAgent(String channelOrAgent) {
        if (!StringUtils.hasText(channelOrAgent)) {
            return false;
        }
        String norm = channelOrAgent.trim().toLowerCase(Locale.ROOT);
        if (THOUGHT_CHANNEL_ALIASES.contains(norm)) {
            return true;
        }
        return norm.contains("think") && !norm.contains("unthink");
    }

    private static boolean isThoughtPhaseOrRole(String s) {
        if (!StringUtils.hasText(s)) {
            return false;
        }
        String l = s.toLowerCase(Locale.ROOT);
        return l.contains("think") || l.contains("reason") || l.contains("internal");
    }

    private static String extractDeltaText(JsonNode node) {
        String d = node.path("delta").asText("");
        if (StringUtils.hasText(d)) {
            return d;
        }
        d = node.path("text").asText("");
        if (StringUtils.hasText(d)) {
            return d;
        }
        d = node.path("content").asText("");
        if (StringUtils.hasText(d)) {
            return d;
        }
        return node.path("chunk").asText("");
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

