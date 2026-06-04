package io.github.uou_capstone.aiplatform.domain.exam.studio.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseAccessService;
import io.github.uou_capstone.aiplatform.domain.exam.studio.dto.ExamStudioChatRequest;
import io.github.uou_capstone.aiplatform.domain.exam.studio.dto.ExamStudioPdfContextRequest;
import io.github.uou_capstone.aiplatform.domain.material.entity.Material;
import io.github.uou_capstone.aiplatform.domain.material.repository.MaterialRepository;
import io.github.uou_capstone.aiplatform.integration.fastapi.FastApiBridgeClient;
import io.github.uou_capstone.aiplatform.util.sse.SseStreamPolicy;
import io.github.uou_capstone.aiplatform.util.sse.SseStreamSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exam Studio 서비스 — 교사 대상 대화형 시험 작성 보조.
 *
 * <p>두 호출:
 * <ul>
 *   <li>{@link #issuePdfContext}: PDF 컨텍스트 1회 발급 (단건 JSON).</li>
 *   <li>{@link #streamChat}: 발급된 {@code contextId} 와 메시지로 AI 와 대화 (SSE 스트림).</li>
 * </ul>
 *
 * <p>FastAPI의 {@code contextId} 는 process memory 기반 best-effort cache 라
 * TTL 만료/재시작/multi-worker 라우팅 변경 시 사라질 수 있다. Spring은 미존재/만료 응답을
 * 받으면 context 재생성 후 재시도해야 하지만, NDJSON 스트림 도중 retry는 FE 측 contextId 재발급
 * 흐름으로 위임한다 (TODO: 자동 retry 1회 추가 시 별도 PR).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamStudioService {

    private static final Duration CHAT_IDLE_TIMEOUT = Duration.ofSeconds(180);

    private final CourseAccessService courseAccessService;
    private final MaterialRepository materialRepository;
    private final FastApiBridgeClient fastApiBridgeClient;
    private final ObjectMapper objectMapper;

    public Map<String, Object> issuePdfContext(Long courseId, ExamStudioPdfContextRequest req) {
        MaterialContext ctx = resolveMaterialContext(courseId, req.getMaterialId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pdfPath", ctx.filePath());
        body.put("pdf_path", ctx.filePath());
        body.put("courseId", courseId);
        body.put("lectureId", ctx.lectureId());
        body.put("materialId", ctx.materialId());
        body.put("displayName", ctx.displayName());

        log.info("Exam Studio PDF context 발급 요청: courseId={}, materialId={}, path={}",
                courseId, ctx.materialId(), ctx.filePath());

        String raw = fastApiBridgeClient.examStudioPdfContext(body);
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new BusinessException(CommonErrorCode.AI_SERVER_ERROR,
                    "Exam Studio PDF 컨텍스트 응답 파싱에 실패했습니다.");
        }
    }

    public Flux<ServerSentEvent<Map<String, Object>>> streamChat(Long courseId, ExamStudioChatRequest req) {
        courseAccessService.loadCourseAsTeacher(courseId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contextId", req.getContextId());
        if (req.getMessages() != null) {
            body.put("messages", req.getMessages());
        }
        if (req.getMessage() != null) {
            body.put("message", req.getMessage());
        }
        body.put("currentDraft", req.getCurrentDraft() == null ? Map.of() : req.getCurrentDraft());
        body.put("currentKstIso", req.getCurrentKstIso());
        body.put("timeZone", req.getTimeZone());
        body.put("sourceText", req.getSourceText());
        body.put("model", req.getModel());

        log.info("Exam Studio chat stream 시작: courseId={}, contextId={}", courseId, req.getContextId());

        Flux<String> upstream = fastApiBridgeClient.examStudioChatStream(body);
        SseStreamPolicy policy = SseStreamPolicy.builder()
                .idleTimeout(CHAT_IDLE_TIMEOUT)
                .heartbeatPassthrough(true)
                .appendDoneOnComplete(false)
                .build();
        return SseStreamSupport.wrapNdjsonByType(upstream, objectMapper, policy, this::mapError);
    }

    private MaterialContext resolveMaterialContext(Long courseId, Long materialId) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        Material material = materialRepository.findByIdWithLectureAndCourse(materialId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        Long materialCourseId = material.getLecture().getCourse().getId();
        if (!materialCourseId.equals(course.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        String materialType = material.getMaterialType();
        if (materialType == null || !"PDF".equalsIgnoreCase(materialType)) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER,
                    "Exam Studio 는 PDF 자료만 지원합니다 (materialType=" + materialType + ").");
        }
        String rawPath = material.getFilePath();
        if (rawPath == null || rawPath.isBlank()) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER,
                    "Material 의 PDF 파일 경로가 비어 있습니다. 업로드를 다시 시도하세요.");
        }
        // 개발 환경(Windows) 에서 backslash 가 섞일 수 있음 — POSIX 형태로 정규화.
        // FastAPI 는 POSIX path 만 받음.
        String filePath = rawPath.replace('\\', '/');

        // FastAPI 보안 정책 (BRIDGE_AGENT_ENDPOINTS.md): pdfPath 는 uploads/ 하위·.pdf 확장자만 허용.
        // 동일 정책을 Spring 측에서 선제 검증 — path mismatch 가 AI 서버 오류로 보이는 것을 방지.
        // (%PDF- header 검증은 Spring 과 FastAPI 가 같은 볼륨을 공유해야 가능 — 인프라 보장 후 추가)
        if (!filePath.toLowerCase().endsWith(".pdf")) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER,
                    "Material 파일이 .pdf 확장자가 아닙니다: " + filePath);
        }
        if (!filePath.startsWith("uploads/") && !filePath.contains("/uploads/")) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER,
                    "Material 경로가 uploads/ 하위가 아닙니다. 업로드 정책을 확인하세요.");
        }

        return new MaterialContext(material.getId(), material.getLecture().getId(),
                filePath, material.getDisplayName());
    }

    private Map<String, Object> mapError(Throwable e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "error");
        m.put("code", "AI_SERVER_ERROR");
        if (e instanceof WebClientResponseException ex) {
            log.error("FastAPI exam_studio/chat_stream 오류: status={}", ex.getStatusCode(), e);
            m.put("message", "AI 서비스 호출에 실패했습니다.");
        } else {
            log.error("FastAPI exam_studio/chat_stream 알 수 없는 오류", e);
            m.put("message", "AI 서비스 호출 중 오류가 발생했습니다.");
        }
        return m;
    }

    private record MaterialContext(Long materialId, Long lectureId, String filePath, String displayName) {}
}
