package io.github.uou_capstone.aiplatform.domain.exam.service;

import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.agent.StreamingEvent;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamSessionRepository;
import io.github.uou_capstone.aiplatform.integration.fastapi.FastApiBridgeClient;
import io.github.uou_capstone.aiplatform.domain.material.entity.Material;
import io.github.uou_capstone.aiplatform.domain.material.generation.GenerationSessionRepository;
import io.github.uou_capstone.aiplatform.domain.material.repository.MaterialRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.domain.user.repository.UserRepository;
import io.github.uou_capstone.aiplatform.util.NdjsonLineFilters;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

/**
 * 시험 생성 스트리밍 서비스 (v3)
 *
 * FastAPI POST /api/v3/bridge/quiz 스트림을 그대로 받아 NDJSON 이벤트를 StreamingEvent로 변환한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamGenerationStreamService {

    private final ExamSessionRepository examSessionRepository;
    private final MaterialRepository materialRepository;
    private final UserRepository userRepository;
    private final CurrentUserResolver currentUserResolver;
    private final GenerationSessionRepository generationSessionRepository;
    private final ObjectMapper objectMapper;
    private final FastApiBridgeClient fastApiBridgeClient;

    /**
     * 시험 생성 스트리밍
     *
     * FastAPI POST /api/v3/bridge/quiz 를 호출하여 NDJSON 스트림을 StreamingEvent Flux로 변환한다.
     *
     * @param examSessionId 시험 세션 ID
     * @return 스트리밍 이벤트 Flux
     */
    @Transactional(readOnly = true)
    public Flux<StreamingEvent> streamExamGeneration(Long examSessionId) {
        log.info("시험 생성 스트리밍 시작: examSessionId={}", examSessionId);

        ExamSession session = examSessionRepository.findById(examSessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));
        validateSessionOwner(session);

        String lectureContent = getLectureContent(session);

        Map<String, Object> body = new HashMap<>();
        // 동기 v2 생성과 동일한 Bridge용 exam_type 문자열 (enum.name() 아님)
        body.put("exam_type", toBridgeExamType(session.getExamType()));
        body.put("target_count", session.getTargetCount() != null ? session.getTargetCount() : 10);
        body.put("lecture_content", lectureContent);
        if (session.getPriorProfileJson() != null) {
            body.put("user_profile", session.getPriorProfileJson());
        }

        return fastApiBridgeClient.streamQuiz(body)
                .filter(line -> !line.isBlank())
                .filter(line -> !NdjsonLineFilters.isHeartbeatLine(objectMapper, line))
                .map(this::toStreamingEvent)
                .onErrorResume(e -> {
                    log.error("시험 생성 스트리밍 오류: examSessionId={}", examSessionId, e);
                    return Flux.just(StreamingEvent.builder()
                            .type("error")
                            .delta("시험 생성 스트리밍 중 오류가 발생했습니다: " + e.getMessage())
                            .build());
                });
    }

    private static String toBridgeExamType(ExamType examType) {
        return switch (examType) {
            case FLASH_CARD -> "Flash_Card";
            case OX_PROBLEM -> "OX_Problem";
            case FIVE_CHOICE -> "Five_Choice";
            case SHORT_ANSWER -> "Short_Answer";
            case DEBATE -> "Debate";
        };
    }

    private String getLectureContent(ExamSession session) {
        Material pdfMaterial = session.getMaterial();
        if (pdfMaterial == null) {
            pdfMaterial = materialRepository
                    .findFirstByLecture_IdAndMaterialTypeOrderByCreatedAtDesc(session.getLecture().getId(), "PDF")
                    .orElse(null);
        }

        if (pdfMaterial != null) {
            String pdfText = io.github.uou_capstone.aiplatform.util.PdfTextExtractor
                    .extractTextIfLocal(pdfMaterial.getFilePath());
            if (pdfText != null && !pdfText.isBlank()) return pdfText;
            return pdfMaterial.getFilePath();
        }

        io.github.uou_capstone.aiplatform.domain.material.generation.GenerationSession generationSession =
                generationSessionRepository.findByLectureIdOrderByCreatedAtDesc(session.getLecture().getId())
                        .stream()
                        .filter(s -> s.getFinalDocument() != null)
                        .findFirst()
                        .orElse(null);

        if (generationSession != null && generationSession.getFinalDocument() != null) {
            return generationSession.getFinalDocument();
        }

        throw new BusinessException(CommonErrorCode.FILE_NOT_FOUND,
                "강의 자료를 찾을 수 없습니다. PDF를 업로드하거나 강의 내용을 제공해주세요.");
    }

    private void validateSessionOwner(ExamSession session) {
        User currentUser = currentUserResolver.getUser();

        if (!session.getUser().getId().equals(currentUser.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }

    private StreamingEvent toStreamingEvent(String line) {
        try {
            JsonNode root = objectMapper.readTree(line);
            String type = root.path("type").asText("unknown");

            Map<String, Object> metadata = objectMapper.convertValue(
                    root, new TypeReference<Map<String, Object>>() {});
            metadata.remove("type");
            metadata.remove("delta");

            return StreamingEvent.builder()
                    .type(type)
                    .delta(root.has("delta") ? root.get("delta").asText() : null)
                    .metadata(metadata.isEmpty() ? null : metadata)
                    .build();
        } catch (Exception e) {
            log.warn("시험 생성 NDJSON 파싱 실패: {}", line, e);
            return StreamingEvent.builder()
                    .type("raw")
                    .delta(line)
                    .build();
        }
    }
}
