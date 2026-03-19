package io.github.uou_capstone.aiplatform.domain.exam.service;

import io.github.uou_capstone.aiplatform.agent.StreamingEvent;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamSessionRepository;
import io.github.uou_capstone.aiplatform.domain.material.entity.Material;
import io.github.uou_capstone.aiplatform.domain.material.generation.GenerationSessionRepository;
import io.github.uou_capstone.aiplatform.domain.material.repository.MaterialRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

/**
 * 시험 생성 스트리밍 서비스 (v3)
 *
 * 개별 GeneratorAgent 호출을 제거하고 FastAPI POST /bridge/quiz/stream 으로 위임한다.
 * FastAPI의 NDJSON 스트림을 StreamingEvent로 변환하여 클라이언트에 전달한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamGenerationStreamService {

    private final ExamSessionRepository examSessionRepository;
    private final MaterialRepository materialRepository;
    private final UserRepository userRepository;
    private final GenerationSessionRepository generationSessionRepository;
    private final WebClient aiServiceWebClient;

    /**
     * 시험 생성 스트리밍
     *
     * FastAPI POST /bridge/quiz/stream 을 호출하여 NDJSON 스트림을 StreamingEvent Flux로 변환한다.
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

        String lectureContent = getLectureContent(session.getLecture().getId());

        Map<String, Object> body = new HashMap<>();
        body.put("exam_type", session.getExamType().name());
        body.put("target_count", session.getTargetCount() != null ? session.getTargetCount() : 10);
        body.put("lecture_content", lectureContent);
        if (session.getPriorProfileJson() != null) {
            body.put("user_profile", session.getPriorProfileJson());
        }

        return aiServiceWebClient.post()
                .uri("/bridge/quiz/stream")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(line -> !line.isBlank())
                .map(line -> StreamingEvent.builder()
                        .type("answer")
                        .delta(line)
                        .build())
                .onErrorResume(e -> {
                    log.error("시험 생성 스트리밍 오류: examSessionId={}", examSessionId, e);
                    return Flux.just(StreamingEvent.builder()
                            .type("error")
                            .delta("시험 생성 스트리밍 중 오류가 발생했습니다: " + e.getMessage())
                            .build());
                });
    }

    private String getLectureContent(Long lectureId) {
        Material pdfMaterial = materialRepository
                .findFirstByLecture_IdAndMaterialTypeOrderByCreatedAtDesc(lectureId, "PDF")
                .orElse(null);

        if (pdfMaterial != null) {
            String pdfText = io.github.uou_capstone.aiplatform.util.PdfTextExtractor
                    .extractTextIfLocal(pdfMaterial.getFilePath());
            if (pdfText != null && !pdfText.isBlank()) return pdfText;
            return pdfMaterial.getFilePath();
        }

        io.github.uou_capstone.aiplatform.domain.material.generation.GenerationSession generationSession =
                generationSessionRepository.findByLectureIdOrderByCreatedAtDesc(lectureId)
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
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

        if (!session.getUser().getId().equals(currentUser.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
