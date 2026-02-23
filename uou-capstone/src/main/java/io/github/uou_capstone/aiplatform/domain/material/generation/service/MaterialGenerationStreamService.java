package io.github.uou_capstone.aiplatform.domain.material.generation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.agent.AgentRequest;
import io.github.uou_capstone.aiplatform.agent.StreamingEvent;
import io.github.uou_capstone.aiplatform.agent.material.*;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.material.generation.GenerationPhase;
import io.github.uou_capstone.aiplatform.domain.material.generation.GenerationSession;
import io.github.uou_capstone.aiplatform.domain.material.generation.GenerationSessionRepository;
import io.github.uou_capstone.aiplatform.domain.material.repository.MaterialRepository;
import io.github.uou_capstone.aiplatform.domain.material.entity.Material;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

/**
 * 강의 자료 생성 스트리밍 서비스
 * Agent 추론 과정을 실시간으로 스트리밍하는 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialGenerationStreamService {

    // ========== 의존성 주입 ==========
    private final PlanningAgent planningAgent;
    private final ConfirmAgent confirmAgent;
    private final DecompositionAgent decompositionAgent;
    private final WriteAgent writeAgent;
    private final ValidationAgent validationAgent;
    private final ReviewAgent reviewAgent;
    private final EditorAgent editorAgent;
    private final GenerationSessionRepository generationSessionRepository;
    private final MaterialRepository materialRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    /**
     * Phase 1 스트리밍
     * PlanningAgent의 추론 과정을 실시간으로 스트리밍
     * 
     * @param sessionId 세션 ID
     * @return 스트리밍 이벤트 Flux
     */
    @Transactional(readOnly = true)
    public Flux<StreamingEvent> streamPhase1(Long sessionId) {
        log.info("Phase 1 스트리밍 시작: sessionId={}", sessionId);

        // ========== 1단계: 세션 조회 및 권한 확인 ==========
        GenerationSession session = generationSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));
        validateSessionOwner(session);

        // ========== 2단계: AgentRequest 구성 ==========
        String keyword = session.getUserPrompt();
        Material pdfMaterial = materialRepository
                .findFirstByLecture_IdAndMaterialTypeOrderByCreatedAtDesc(
                        session.getLecture().getId(),
                        "PDF"
                )
                .orElse(null);
        String pdfPath = pdfMaterial != null ? pdfMaterial.getFilePath() : null;

        Map<String, Object> context = new HashMap<>();
        context.put("keyword", keyword);
        context.put("pdf_path", pdfPath);

        AgentRequest request = new SimpleAgentRequest(keyword, context);

        // ========== 3단계: 스트리밍 실행 ==========
        return planningAgent.executeStreaming(request);
    }

    /**
     * Phase 2 스트리밍
     * ConfirmAgent 또는 UpdateAgent의 추론 과정을 실시간으로 스트리밍
     * 
     * @param sessionId 세션 ID
     * @param userFeedback 사용자 피드백 (선택적, null이면 ConfirmAgent 사용)
     * @return 스트리밍 이벤트 Flux
     */
    @Transactional(readOnly = true)
    public Flux<StreamingEvent> streamPhase2(Long sessionId, String userFeedback) {
        log.info("Phase 2 스트리밍 시작: sessionId={}, hasFeedback={}", sessionId, userFeedback != null);

        // ========== 1단계: 세션 조회 및 권한 확인 ==========
        GenerationSession session = generationSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));
        validateSessionOwner(session);

        // ========== 2단계: AgentRequest 구성 ==========
        Map<String, Object> draftPlanMap = session.getDraftPlanJson();
        if (draftPlanMap == null) {
            throw new BusinessException(CommonErrorCode.DATA_NOT_FOUND, "DraftPlan이 없습니다.");
        }

        Map<String, Object> context = new HashMap<>();
        context.put("draft_plan", draftPlanMap);
        
        // 사용자 피드백이 있으면 UpdateAgent 사용, 없으면 ConfirmAgent 사용
        if (userFeedback != null && !userFeedback.trim().isEmpty()) {
            context.put("user_feedback", userFeedback);
            AgentRequest request = new SimpleAgentRequest("Update draft plan based on user feedback", context);
            // UpdateAgent는 스트리밍을 지원하지 않을 수 있으므로, ConfirmAgent로 대체
            // 향후 UpdateAgent에 스트리밍 지원 추가 시 변경 가능
            return confirmAgent.executeStreaming(request);
        } else {
            AgentRequest request = new SimpleAgentRequest("Process feedback for draft plan", context);
            return confirmAgent.executeStreaming(request);
        }
    }

    /**
     * Phase 3 스트리밍
     * DecompositionAgent와 WriteAgent의 추론 과정을 실시간으로 스트리밍
     * 
     * @param sessionId 세션 ID
     * @return 스트리밍 이벤트 Flux
     */
    @Transactional(readOnly = true)
    public Flux<StreamingEvent> streamPhase3(Long sessionId) {
        log.info("Phase 3 스트리밍 시작: sessionId={}", sessionId);

        // ========== 1단계: 세션 조회 및 권한 확인 ==========
        GenerationSession session = generationSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));
        validateSessionOwner(session);

        // ========== 2단계: AgentRequest 구성 ==========
        Map<String, Object> finalizedBriefMap = session.getFinalizedBriefJson();
        if (finalizedBriefMap == null) {
            throw new BusinessException(CommonErrorCode.DATA_NOT_FOUND, "FinalizedBrief가 없습니다.");
        }

        Material pdfMaterial = materialRepository
                .findFirstByLecture_IdAndMaterialTypeOrderByCreatedAtDesc(
                        session.getLecture().getId(),
                        "PDF"
                )
                .orElse(null);
        String pdfPath = pdfMaterial != null ? pdfMaterial.getFilePath() : null;

        Map<String, Object> context = new HashMap<>();
        context.put("finalized_brief", finalizedBriefMap);
        context.put("pdf_path", pdfPath);

        AgentRequest request = new SimpleAgentRequest("Decompose chapters and write content", context);

        // ========== 3단계: 스트리밍 실행 ==========
        // DecompositionAgent와 WriteAgent를 순차적으로 스트리밍
        // 먼저 DecompositionAgent 스트리밍, 그 다음 WriteAgent 스트리밍
        return decompositionAgent.executeStreaming(request)
                .concatWith(writeAgent.executeStreaming(request));
    }

    /**
     * Phase 4 스트리밍
     * ValidationAgent와 ReviewAgent의 추론 과정을 실시간으로 스트리밍
     * 
     * @param sessionId 세션 ID
     * @return 스트리밍 이벤트 Flux
     */
    @Transactional(readOnly = true)
    public Flux<StreamingEvent> streamPhase4(Long sessionId) {
        log.info("Phase 4 스트리밍 시작: sessionId={}", sessionId);

        // ========== 1단계: 세션 조회 및 권한 확인 ==========
        GenerationSession session = generationSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));
        validateSessionOwner(session);

        // ========== 2단계: AgentRequest 구성 ==========
        Map<String, Object> chapterContentListMap = session.getChapterContentListJson();
        if (chapterContentListMap == null) {
            throw new BusinessException(CommonErrorCode.DATA_NOT_FOUND, "ChapterContentList가 없습니다.");
        }

        Map<String, Object> context = new HashMap<>();
        context.put("chapter_content_list", chapterContentListMap);

        AgentRequest request = new SimpleAgentRequest("Validate and review content", context);

        // ========== 3단계: 스트리밍 실행 ==========
        // ValidationAgent와 ReviewAgent를 순차적으로 스트리밍
        return validationAgent.executeStreaming(request)
                .concatWith(reviewAgent.executeStreaming(request));
    }

    /**
     * Phase 5 스트리밍
     * EditorAgent의 추론 과정을 실시간으로 스트리밍
     * 
     * @param sessionId 세션 ID
     * @return 스트리밍 이벤트 Flux
     */
    @Transactional(readOnly = true)
    public Flux<StreamingEvent> streamPhase5(Long sessionId) {
        log.info("Phase 5 스트리밍 시작: sessionId={}", sessionId);

        // ========== 1단계: 세션 조회 및 권한 확인 ==========
        GenerationSession session = generationSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));
        validateSessionOwner(session);

        // ========== 2단계: AgentRequest 구성 ==========
        Map<String, Object> verifiedContentMap = session.getVerifiedContentJson();
        if (verifiedContentMap == null) {
            throw new BusinessException(CommonErrorCode.DATA_NOT_FOUND, "VerifiedContent가 없습니다.");
        }

        Map<String, Object> context = new HashMap<>();
        context.put("verified_content", verifiedContentMap);

        AgentRequest request = new SimpleAgentRequest("Assemble final markdown document", context);

        // ========== 3단계: 스트리밍 실행 ==========
        return editorAgent.executeStreaming(request);
    }

    /**
     * 세션 소유자 확인
     */
    private void validateSessionOwner(GenerationSession session) {
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

        if (!session.getUser().getId().equals(currentUser.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }

    /**
     * 간단한 AgentRequest 구현
     */
    private static class SimpleAgentRequest implements AgentRequest {
        private final String prompt;
        private final Map<String, Object> context;

        public SimpleAgentRequest(String prompt, Map<String, Object> context) {
            this.prompt = prompt;
            this.context = context;
        }

        @Override
        public String getPrompt() {
            return prompt;
        }

        @Override
        public Map<String, Object> getContext() {
            return context;
        }
    }
}
