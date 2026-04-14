package io.github.uou_capstone.aiplatform.domain.learning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.learning.dto.SessionEventRequest;
import io.github.uou_capstone.aiplatform.domain.material.repository.MaterialRepository;
import io.github.uou_capstone.aiplatform.integration.fastapi.FastApiSessionClient;
import io.github.uou_capstone.aiplatform.util.BridgeResponseLogger;
import io.github.uou_capstone.aiplatform.util.NdjsonLineFilters;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 학습 세션 서비스
 *
 * FastAPI OrchestrationEngine의 학습 세션 API를 Spring Boot에서 프록시하는 역할.
 * 실제 세션 상태와 오케스트레이션 로직은 FastAPI가 담당하며,
 * Spring Boot는 인증/인가 처리 후 FastAPI로 요청을 위임한다.
 *
     * 프록시 대상:
     * - POST /api/learning/sessions/{lectureId}     → GET  FastAPI /api/v3/session/by-lecture/{lectureId} (pdf_path, session_id)
     * - POST /api/learning/sessions/{id}/event      → POST FastAPI /api/v3/session/{id}/event/stream (NDJSON→SSE)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningSessionService {

    private final FastApiSessionClient fastApiSessionClient;
    private final ObjectMapper objectMapper;
    private final MaterialRepository materialRepository;

    /**
     * 강의 ID로 학습 세션 조회 또는 신규 생성.
     *
     * FastAPI의 GET /api/v3/session/by-lecture/{lectureId} 를 호출한다.
     * FastAPI가 세션 존재 여부를 확인하여 기존 세션을 반환하거나 새로 생성한다.
     *
     * @param lectureId 강의 ID
     * @return FastAPI 세션 응답 (sessionId, state, aiStatus 포함)
     */
    public Mono<Map<String, Object>> getOrCreateSession(Long lectureId, String pdfPath, Long sessionId) {
        if (lectureId == null || lectureId <= 0) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "유효한 강의 ID가 필요합니다.");
        }

        // pdfPath가 없으면 강의에 업로드된 최신 PDF 자료 경로를 자동으로 조회
        String effectivePdfPath = pdfPath;
        if (!StringUtils.hasText(effectivePdfPath)) {
            effectivePdfPath = materialRepository
                    .findFirstByLecture_IdAndMaterialTypeOrderByCreatedAtDesc(lectureId, "PDF")
                    .map(m -> m.getFilePath())
                    .orElse(null);
            if (StringUtils.hasText(effectivePdfPath)) {
                log.info("강의 PDF 경로 자동 조회: lectureId={}, path={}", lectureId, effectivePdfPath);
            }
        }

        log.info("학습 세션 조회/생성: lectureId={}, hasPdfPath={}, sessionId={}",
                lectureId, StringUtils.hasText(effectivePdfPath), sessionId);

        final String finalPdfPath = effectivePdfPath;
        return fastApiSessionClient.getOrCreateByLecture(lectureId, finalPdfPath, sessionId)
                .doOnNext(body -> BridgeResponseLogger.debugMapSummary(log, "GET /api/v3/session/by-lecture", body))
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
     * FastAPI의 POST /api/v3/session/{sessionId}/event/stream 을 호출하고
     * 반환되는 NDJSON 스트림을 SSE(text/event-stream)로 변환하여 클라이언트에 전달한다.
     *
     * FastAPI NDJSON 이벤트 포맷 예시:
     * {"type":"agent_delta","agent":"explainer","delta":"설명 텍스트..."}
     * {"type":"done","agent":"explainer","tool":"EXPLAIN_PAGE","final":true,"data":{}}
     * {"type":"error","message":"오류 메시지"}
     *
     * @param lectureId   강의 ID (FastAPI EventRequest.lecture_id)
     * @param sessionId   학습 세션 ID (FastAPI 세션 식별자)
     * @param eventRequest 이벤트 요청 (type, payload 포함)
     * @return NDJSON 라인을 SSE data로 래핑한 Flux
     */
    /**
     * @param page        쿼리: 뷰어 현재 페이지(1-based). USER_MESSAGE 등에서 FastAPI가 참조하는 current_page와 동기화
     * @param pageNumber  page 별칭
     * @param currentPage page 별칭
     */
    public Flux<ServerSentEvent<String>> streamSessionEvent(Long lectureId, Long sessionId, SessionEventRequest eventRequest,
                                                            Integer page, Integer pageNumber, Integer currentPage) {
        if (sessionId == null || sessionId <= 0) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "유효한 sessionId가 필요합니다.");
        }
        if (lectureId != null && lectureId <= 0) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "lectureId는 양수여야 합니다.");
        }
        if (eventRequest == null || eventRequest.getType() == null || eventRequest.getType().isBlank()) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "이벤트 타입은 필수입니다.");
        }

        Integer viewerPage = firstNonNullPositive(currentPage, pageNumber, page);
        log.info("학습 세션 이벤트 스트림: lectureId={}, sessionId={}, eventType={}, viewerPage={}",
                lectureId, sessionId, eventRequest.getType(), viewerPage);

        Map<String, Object> payload = buildPayloadForFastApi(eventRequest, viewerPage);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("type", eventRequest.getType());
        if (lectureId != null) {
            requestBody.put("lecture_id", lectureId);
        }
        requestBody.put("payload", payload);

        return fastApiSessionClient.streamEvent(sessionId, requestBody)
                .filter(line -> !line.isBlank())
                .filter(line -> !NdjsonLineFilters.isHeartbeatLine(objectMapper, line))
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
                    log.error("이벤트 스트림 중 알 수 없는 오류: lectureId={}, sessionId={}", lectureId, sessionId, e);
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

    /** 쿼리 파라미터 여러 별칭 중 첫 유효값(양의 정수) */
    private static Integer firstNonNullPositive(Integer a, Integer b, Integer c) {
        for (Integer v : new Integer[]{a, b, c}) {
            if (v != null && v > 0) {
                return v;
            }
        }
        return null;
    }

    /**
     * FastAPI {@code EventRequest} 계약에 맞게 payload를 만든다.
     * <ul>
     *   <li>FE가 {@code { "type":"...", "payload": { ... } }} 형태로내면 이중 래핑을 제거한다.</li>
     *   <li>{@code USER_MESSAGE}: 문서 계약은 {@code payload.question}. 구버전 {@code text}만 있으면 {@code question}으로 복사한다.</li>
     *   <li>뷰어 페이지 쿼리가 있으면 {@code current_page} 등을 주입한다.</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildPayloadForFastApi(SessionEventRequest eventRequest, Integer viewerPage) {
        Map<String, Object> payload = new LinkedHashMap<>(eventRequest.toPayload());
        if (payload.size() == 1 && payload.get("payload") instanceof Map<?, ?> nested) {
            payload = new LinkedHashMap<>((Map<String, Object>) nested);
        }

        if ("USER_MESSAGE".equals(eventRequest.getType())) {
            Object question = payload.get("question");
            boolean questionBlank = question == null
                    || (question instanceof String qs && qs.isBlank());
            if (questionBlank) {
                Object text = payload.get("text");
                if (text != null && StringUtils.hasText(String.valueOf(text))) {
                    payload.put("question", String.valueOf(text));
                }
            }
        }

        if (viewerPage != null) {
            payload.put("current_page", viewerPage);
            payload.put("page", viewerPage);
            payload.put("pageNumber", viewerPage);
        }
        return payload;
    }
}
