package io.github.uou_capstone.aiplatform.domain.learning.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.LectureRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.learning.dto.LearningChatMessageResponse;
import io.github.uou_capstone.aiplatform.domain.learning.dto.LearningChatSessionResponse;
import io.github.uou_capstone.aiplatform.domain.learning.dto.SessionEventRequest;
import io.github.uou_capstone.aiplatform.domain.learning.entity.LearningChatSession;
import io.github.uou_capstone.aiplatform.domain.material.repository.MaterialRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.integration.fastapi.FastApiSessionClient;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import io.github.uou_capstone.aiplatform.util.BridgeResponseLogger;
import io.github.uou_capstone.aiplatform.util.sse.SseStreamPolicy;
import io.github.uou_capstone.aiplatform.util.sse.SseStreamSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
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
    private final LectureRepository lectureRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final CurrentUserResolver currentUserResolver;
    private final LearningChatPersistenceService chatPersistenceService;
    private final LearningSessionEvidenceService sessionEvidenceService;

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

        validateLectureAccess(lectureId);
        User currentUser = currentUserResolver.getUser();

        Long effectiveSessionId = sessionId;
        if (effectiveSessionId == null) {
            LearningChatSession chatSession = chatPersistenceService.getOrCreateActiveSession(lectureId, currentUser);
            effectiveSessionId = chatSession.getId();
        } else {
            chatPersistenceService.getOwnedActiveSession(effectiveSessionId, currentUser.getId(), lectureId);
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
        final Long chatSessionId = effectiveSessionId;
        return fastApiSessionClient.getOrCreateByLecture(lectureId, finalPdfPath, chatSessionId)
                .doOnNext(body -> BridgeResponseLogger.debugMapSummary(log, "GET /api/v3/session/by-lecture", body))
                .map(body -> {
                    Map<String, Object> response = new LinkedHashMap<>(body);
                    response.put("chatSessionId", chatSessionId);
                    return response;
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
        if (lectureId == null || lectureId <= 0) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER,
                    "lectureId 는 권한 검증을 위해 필수입니다.");
        }
        if (eventRequest == null || eventRequest.getType() == null || eventRequest.getType().isBlank()) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "이벤트 타입은 필수입니다.");
        }

        validateLectureAccess(lectureId);
        User currentUser = currentUserResolver.getUser();
        chatPersistenceService.getOwnedActiveSession(sessionId, currentUser.getId(), lectureId);

        Integer viewerPage = firstNonNullPositive(currentPage, pageNumber, page);
        log.info("학습 세션 이벤트 스트림: lectureId={}, sessionId={}, eventType={}, viewerPage={}",
                lectureId, sessionId, eventRequest.getType(), viewerPage);

        Map<String, Object> payload = buildPayloadForFastApi(eventRequest, viewerPage);
        if ("USER_MESSAGE".equals(eventRequest.getType())) {
            chatPersistenceService.saveUserMessage(sessionId, currentUser.getId(), lectureId,
                    stringValue(payload.get("question")), viewerPage);
        } else if ("SAVE_AND_EXIT".equals(eventRequest.getType())) {
            chatPersistenceService.markEnded(sessionId, currentUser.getId(), lectureId);
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("type", eventRequest.getType());
        requestBody.put("lecture_id", lectureId);
        requestBody.put("payload", payload);

        AssistantMessageRecorder recorder = new AssistantMessageRecorder(objectMapper);
        Flux<String> upstream = fastApiSessionClient.streamEvent(sessionId, requestBody)
                .doOnNext(line -> {
                    recorder.accept(line);
                    recordLearningEvidence(line, sessionId, lectureId, currentUser);
                })
                .doOnComplete(() -> {
                    if (recorder.shouldSave()) {
                        chatPersistenceService.saveAssistantMessage(sessionId, currentUser.getId(), lectureId,
                                recorder.content(), viewerPage);
                    }
                });
        return SseStreamSupport.wrapNdjson(upstream, objectMapper, SseStreamPolicy.defaults(), e -> {
            if (e instanceof WebClientResponseException ex) {
                log.error("FastAPI 이벤트 스트림 오류: sessionId={}, status={}", sessionId, ex.getStatusCode());
                String safeMsg = ex.getMessage() != null ? ex.getMessage().replace("\"", "'") : "알 수 없는 오류";
                return String.format("{\"type\":\"error\",\"message\":\"AI 서비스 오류(%s): %s\"}",
                        ex.getStatusCode(), safeMsg);
            }
            log.error("이벤트 스트림 중 알 수 없는 오류: lectureId={}, sessionId={}", lectureId, sessionId, e);
            String safeMsg = e.getMessage() != null ? e.getMessage().replace("\"", "'") : "알 수 없는 오류";
            return String.format("{\"type\":\"error\",\"message\":\"%s\"}", safeMsg);
        });
    }

    private void recordLearningEvidence(String line, Long sessionId, Long lectureId, User currentUser) {
        if (line == null || !line.contains("learningEvidence")) {
            return;
        }
        try {
            Mono.fromRunnable(() -> sessionEvidenceService.saveFromStreamLine(line, sessionId, lectureId, currentUser))
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnError(e -> log.warn("Integrated learning evidence save skipped: sessionId={}, lectureId={}, reason={}",
                            sessionId, lectureId, e.getMessage()))
                    .subscribe(null, ignored -> {
                    });
        } catch (Exception e) {
            log.warn("Integrated learning evidence save skipped: sessionId={}, lectureId={}, reason={}",
                    sessionId, lectureId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<LearningChatSessionResponse> getChatSessions(Long lectureId, Pageable pageable) {
        validateLectureAccess(lectureId);
        Long userId = currentUserResolver.getUser().getId();
        return chatPersistenceService.getSessions(lectureId, userId, pageable);
    }

    @Transactional(readOnly = true)
    public List<LearningChatMessageResponse> getChatMessages(Long sessionId) {
        Long userId = currentUserResolver.getUser().getId();
        LearningChatSession session = chatPersistenceService.getOwnedSession(sessionId, userId);
        validateLectureAccess(session.getLecture().getId());
        return chatPersistenceService.getMessages(sessionId, userId);
    }

    /**
     * 강의실 접근 권한 검증.
     * - TEACHER 는 해당 강의가 속한 course 의 소유 교사여야 한다.
     * - STUDENT 는 해당 course 에 활성 Enrollment 가 있어야 한다.
     * 둘 다 아니면 FORBIDDEN.
     */
    void validateLectureAccess(Long lectureId) {
        Lecture lecture = lectureRepository.findByIdWithCourse(lectureId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.LECTURE_NOT_FOUND));
        Course course = lecture.getCourse();

        User currentUser = currentUserResolver.getUser();

        boolean isTeacherOfCourse = currentUser.getTeacher() != null
                && currentUser.getTeacher().getId().equals(course.getTeacher().getId());
        boolean isStudentEnrolled = currentUser.getStudent() != null
                && enrollmentRepository.existsByStudentAndCourse(currentUser.getStudent(), course);

        if (!isTeacherOfCourse && !isStudentEnrolled) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
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

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static class AssistantMessageRecorder {
        private final ObjectMapper objectMapper;
        private final StringBuilder content = new StringBuilder();
        private boolean done;

        AssistantMessageRecorder(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        void accept(String line) {
            try {
                Map<String, Object> payload = objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {});
                Object type = payload.get("type");
                if ("agent_delta".equals(type) && "main".equals(payload.get("channel"))) {
                    Object delta = payload.get("delta");
                    if (delta != null) {
                        content.append(delta);
                    }
                } else if ("done".equals(type)) {
                    done = true;
                }
            } catch (Exception ignored) {
                log.debug("AI stream line skipped for chat persistence: {}", line);
            }
        }

        boolean shouldSave() {
            return done && StringUtils.hasText(content);
        }

        String content() {
            return content.toString();
        }
    }
}
