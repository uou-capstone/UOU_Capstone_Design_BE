package io.github.uou_capstone.aiplatform.domain.material.generation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.agent.material.*;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.LectureRepository;
import io.github.uou_capstone.aiplatform.domain.material.generation.GenerationPhase;
import io.github.uou_capstone.aiplatform.domain.material.generation.GenerationSession;
import io.github.uou_capstone.aiplatform.domain.material.generation.GenerationSessionRepository;
import io.github.uou_capstone.aiplatform.domain.material.generation.dto.*;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.integration.fastapi.FastApiNoteGenClient;
import io.github.uou_capstone.aiplatform.service.AsyncTaskService;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import io.github.uou_capstone.aiplatform.service.CacheService;
import io.github.uou_capstone.aiplatform.service.SessionRecoveryService;
import io.github.uou_capstone.aiplatform.util.AuthorizationUtil;
import io.github.uou_capstone.aiplatform.domain.task.entity.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 강의 자료 생성 서비스
 * Version 2의 5단계 파이프라인을 관리하는 서비스
 * * Phase 1: Analysis & Scope (기획)
 * Phase 2: Interactive Briefing (기획 검토 및 수정)
 * Phase 3: Content Generation (심층 조사 및 집필)
 * Phase 4: Review & Verification (검증 및 수정)
 * Phase 5: Final Assembly (최종 조립)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialGenerationService {

    // ========== 의존성 주입 ==========
    private final PlanningAgent planningAgent;        // Phase 1 Agent
    private final ConfirmAgent confirmAgent;          // Phase 2 Agent
    private final UpdateAgent updateAgent;            // Phase 2 Agent (피드백 처리)
    private final DecompositionAgent decompositionAgent; // Phase 3 Agent
    private final WriteAgent writeAgent;              // Phase 3 Agent
    private final ValidationAgent validationAgent;    // Phase 4 Agent
    private final ReviewAgent reviewAgent;            // Phase 4 Agent
    private final EditorAgent editorAgent;            // Phase 5 Agent
    private final GenerationSessionRepository generationSessionRepository;
    private final LectureRepository lectureRepository;
    private final CurrentUserResolver currentUserResolver;
    private final ObjectMapper objectMapper;          // JSON 변환용
    private final AsyncTaskService asyncTaskService;   // 비동기 작업 상태 추적
    private final SessionRecoveryService sessionRecoveryService; // 세션 복구 서비스
    private final FastApiNoteGenClient fastApiNoteGenClient;
    private final CacheService cacheService;          // Redis 캐싱 서비스
    private final StringRedisTemplate redisTemplate;  // Redis Pub/Sub 발행자

    /**
     * Phase 1: 초기 키워드 기반 DraftPlan 생성
     * * 로직 설명:
     * 1. 권한 확인: 현재 로그인한 사용자가 해당 강의의 소유자인지 확인
     * 2. 강의 정보 조회: lectureId로 Lecture 엔티티 조회
     * 3. 세션 생성: GenerationSession 엔티티 생성 (PHASE1 상태로 시작)
     * 4. Agent 호출: PlanningAgent를 통해 DraftPlan 생성 (키워드 기반)
     * 5. 결과 저장: 생성된 DraftPlan을 JSON으로 변환하여 세션에 저장
     * 6. 응답 반환: sessionId와 DraftPlan을 포함한 응답 반환
     */
    @Transactional
    public MaterialGenerationPhase1ResponseDto startPhase1(MaterialGenerationPhase1RequestDto requestDto) {
        log.info("Phase 1 시작: lectureId={}, keyword={}", requestDto.getLectureId(), requestDto.getKeyword());

        // ========== 1단계: 권한 확인 ==========
        User currentUser = currentUserResolver.getUser();

        // ========== 2단계: 강의 정보 조회 ==========
        Lecture lecture = lectureRepository.findById(requestDto.getLectureId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.LECTURE_NOT_FOUND));

        // ========== 2-1단계: 권한 확인 ==========
        // 현재 사용자가 해당 강의의 소유자인지 확인 (교사 권한 + 강의 소유권 확인)
        AuthorizationUtil.requireLectureOwner(currentUser, lecture);

        // ========== 3단계: 세션 생성 ==========
        GenerationSession session = GenerationSession.builder()
                .lecture(lecture)
                .user(currentUser)
                .userPrompt(requestDto.getKeyword())
                .build();
        session = generationSessionRepository.save(session);
        log.info("GenerationSession 생성 완료: sessionId={}", session.getId());

        // ========== 4단계: Agent 호출 ==========
        DraftPlanDto draftPlan;
        try {
            draftPlan = planningAgent.generateDraftPlan(requestDto.getKeyword());
            log.info("PlanningAgent 호출 완료. DraftPlan 생성.");
        } catch (Exception e) {
            log.error("Phase 1 실패: sessionId={}, error={}", session.getId(), e.getMessage(), e);
            String failureDetail = "Phase 1 실패: " + e.getMessage();
            sessionRecoveryService.handleGenerationSessionFailure(
                    session.getId(), 
                    GenerationPhase.PHASE1, 
                    failureDetail
            );
            String userMessage = mapAgentErrorToUserMessage(e);
            throw new BusinessException(CommonErrorCode.AGENT_EXECUTION_FAILED, userMessage);
        }

        // ========== 5.5단계: 챕터 order/estimated_sections 기본값 채우기 ==========
        normalizeDraftPlanChapters(draftPlan);

        // ========== 6단계: 결과 저장 ==========
        Map<String, Object> draftPlanMap = objectMapper.convertValue(draftPlan, Map.class);
        session.updateDraftPlan(draftPlanMap);
        session.updatePhase(GenerationPhase.PHASE1);
        session.updateProgress(20);  // Phase 1 종료 = 20% 진행
        generationSessionRepository.save(session);

        // Redis에 DraftPlan 캐싱 (FastAPI와 공유, 24시간 TTL)
        String draftPlanCacheKey = "shared:draft_plan:" + session.getId();
        cacheService.set(draftPlanCacheKey, draftPlan, 86400); 

        // ========== 7단계: 응답 반환 ==========
        return MaterialGenerationPhase1ResponseDto.builder()
                .sessionId(session.getId())
                .draftPlan(draftPlan)
                .progressPercentage(20)
                .message("Phase 1 완료: 기획안 초안이 생성되었습니다.")
                .build();
    }

    /**
     * Phase 2: 사용자 피드백 기반 FinalizedBrief 생성
     */
    @Transactional
    public MaterialGenerationPhase2ResponseDto processPhase2(MaterialGenerationPhase2RequestDto requestDto) {
        String normalizedAction = normalizePhase2Action(requestDto.getAction(), requestDto.getFeedback());
        log.info("Phase 2 처리: sessionId={}, action={} (normalized={})",
                requestDto.getSessionId(), requestDto.getAction(), normalizedAction);

        // ========== 1단계: 세션 조회 ==========
        GenerationSession session = generationSessionRepository.findByIdWithLecture(requestDto.getSessionId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        // ========== 2단계: 권한 확인 ==========
        User currentUser = currentUserResolver.getUser();
        if (!session.getUser().getId().equals(currentUser.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // ========== 3단계: Phase 확인 ==========
        GenerationPhase currentPhase = session.getCurrentPhase();
        if ("confirm".equals(normalizedAction)) {
            if (currentPhase != GenerationPhase.PHASE1) {
                throw new BusinessException(
                        CommonErrorCode.INVALID_PHASE,
                        "이미 기획안이 확정된 상태입니다. 수정을 원하시면 수정 사항을 입력해 주세요."
                );
            }
        } else {
            if (currentPhase != GenerationPhase.PHASE1 && currentPhase != GenerationPhase.PHASE2) {
                throw new BusinessException(
                        CommonErrorCode.INVALID_PHASE,
                        "기획안 수정은 Phase 1 또는 Phase 2(되돌리기 후) 상태에서만 가능합니다."
                );
            }
        }

        // ========== 4단계: 수정 기준 기획안 조회 ==========
        DraftPlanDto draftPlan;
        if (currentPhase == GenerationPhase.PHASE1) {
            Map<String, Object> draftPlanMap = session.getDraftPlanJson();
            if (draftPlanMap == null) {
                throw new BusinessException(CommonErrorCode.DATA_NOT_FOUND, "DraftPlan이 없습니다.");
            }
            draftPlan = objectMapper.convertValue(draftPlanMap, DraftPlanDto.class);
        } else {
            Map<String, Object> finalizedBriefMap = session.getFinalizedBriefJson();
            if (finalizedBriefMap == null) {
                throw new BusinessException(CommonErrorCode.DATA_NOT_FOUND, "확정 기획안이 없습니다.");
            }
            draftPlan = objectMapper.convertValue(finalizedBriefMap, DraftPlanDto.class);
        }

        // ========== 5단계: 사용자 피드백 처리 ==========
        if ("confirm".equals(normalizedAction)) {
            FinalizedBriefDto finalizedBrief = new FinalizedBriefDto();
            finalizedBrief.setProjectMeta(draftPlan.getProjectMeta());
            finalizedBrief.setStyleGuide(draftPlan.getStyleGuide());
            finalizedBrief.setChapters(draftPlan.getChapters());

            Map<String, Object> finalizedBriefMap = objectMapper.convertValue(finalizedBrief, Map.class);
            session.updateFinalizedBrief(finalizedBriefMap);
            session.updatePhase(GenerationPhase.PHASE2);
            session.updateProgress(40); 
            generationSessionRepository.save(session);
            log.info("Phase 2 confirm 완료: sessionId={}, phase=PHASE2, progress=40%", session.getId());

            String finalizedBriefCacheKey = "shared:finalized_brief:" + session.getId();
            cacheService.set(finalizedBriefCacheKey, finalizedBrief, 86400);

            return MaterialGenerationPhase2ResponseDto.builder()
                    .sessionId(session.getId())
                    .finalizedBrief(finalizedBrief)
                    .progressPercentage(40)
                    .message("Phase 2 완료: 기획안이 확정되었습니다.")
                    .build();
        } else {
            String feedback = requestDto.getFeedback();
            if (feedback == null || feedback.trim().isEmpty()) {
                throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "수정 요청 시 피드백은 필수입니다.");
            }
            
            FinalizedBriefDto finalizedBrief;
            try {
                finalizedBrief = updateAgent.updateDraftPlan(draftPlan, feedback);
            } catch (Exception e) {
                log.error("Phase 2 (Update) 실패: sessionId={}, error={}", requestDto.getSessionId(), e.getMessage(), e);
                sessionRecoveryService.handleGenerationSessionFailure(requestDto.getSessionId(), GenerationPhase.PHASE2, "Phase 2 수정 실패: " + e.getMessage());
                throw new BusinessException(CommonErrorCode.AGENT_EXECUTION_FAILED, "기획안 수정에 실패했습니다: " + e.getMessage());
            }

            Map<String, Object> finalizedBriefMap = objectMapper.convertValue(finalizedBrief, Map.class);
            session.updateFinalizedBrief(finalizedBriefMap);
            session.updatePhase(GenerationPhase.PHASE2);
            session.updateProgress(40);
            generationSessionRepository.save(session);

            String finalizedBriefCacheKey = "shared:finalized_brief:" + session.getId();
            cacheService.set(finalizedBriefCacheKey, finalizedBrief, 86400);
            
            return MaterialGenerationPhase2ResponseDto.builder()
                    .sessionId(session.getId())
                    .finalizedBrief(finalizedBrief)
                    .progressPercentage(40)
                    .message("기획안이 수정 및 확정되었습니다.")
                    .build();
        }
    }

    /**
     * AI 서비스(Gemini) 오류 시 사용자에게 보여줄 메시지 변환
     */
    private String mapAgentErrorToUserMessage(Exception e) {
        String body = null;
        int status = 0;
        if (e instanceof WebClientResponseException wce) {
            status = wce.getStatusCode().value();
            try { body = wce.getResponseBodyAsString(); } catch (Exception ignored) {}
        }
        String combined = (body != null && !body.isBlank()) ? body : e.getMessage();
        if (combined != null) {
            if (combined.contains("high demand") || combined.contains("Please try again later") || combined.contains("UNAVAILABLE")) {
                return "기획안 생성에 실패했습니다. AI 모델의 일시적인 사용량이 많습니다. 잠시 후 다시 시도해 주세요.";
            }
            if (status == 429 || combined.contains("quota") || combined.contains("RESOURCE_EXHAUSTED")) {
                return "API 사용 한도를 초과했습니다. 잠시 후 다시 시도해 주세요.";
            }
        }
        return "기획안 생성에 실패했습니다: " + (e.getMessage() != null ? e.getMessage() : "알 수 없는 오류");
    }

    private String normalizePhase2Action(String action, String feedback) {
        String trimmed = action == null ? "" : action.trim();
        if (trimmed.isEmpty()) {
            return (feedback != null && !feedback.trim().isEmpty()) ? "feedback" : "confirm";
        }
        return trimmed.toLowerCase();
    }

    private void normalizeDraftPlanChapters(DraftPlanDto draftPlan) {
        if (draftPlan == null || draftPlan.getChapters() == null) return;
        List<ChapterDto> chapters = draftPlan.getChapters();
        for (int i = 0; i < chapters.size(); i++) {
            ChapterDto ch = chapters.get(i);
            if (ch.getOrder() == null) ch.setOrder(i + 1);
            if (ch.getEstimatedSections() == null) ch.setEstimatedSections(1);
        }
    }

    @Transactional(readOnly = true)
    public MaterialGenerationStatusDto getStatus(Long sessionId) {
        GenerationSession session = generationSessionRepository.findByIdWithLecture(sessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        User currentUser = currentUserResolver.getUser();
        if (!session.getUser().getId().equals(currentUser.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        return MaterialGenerationStatusDto.builder()
                .sessionId(session.getId())
                .currentPhase(session.getCurrentPhase())
                .progressPercentage(session.getProgressPercentage())
                .errorMessage(session.getErrorMessage())
                .finalDocument(session.getFinalDocument())
                .build();
    }

    /**
     * 가장 최근의 생성 세션을 조회하여 복구(Resume) 정보를 반환
     */
    @Transactional(readOnly = true)
    public MaterialGenerationResumeDto findLatestGenerationSessionByLectureId(Long lectureId) {
        User currentUser = currentUserResolver.getUser();
        GenerationSession session = generationSessionRepository
                .findByLectureAndUserWithLecture(lectureId, currentUser.getId())
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND, "해당 강의의 생성 세션이 없습니다."));

        AuthorizationUtil.requireLectureOwner(currentUser, session.getLecture());

        DraftPlanDto draftPlan = session.getDraftPlanJson() == null ? null : objectMapper.convertValue(session.getDraftPlanJson(), DraftPlanDto.class);
        if (draftPlan != null) normalizeDraftPlanChapters(draftPlan);

        return MaterialGenerationResumeDto.builder()
                .sessionId(session.getId())
                .lectureId(session.getLecture().getId())
                .currentPhase(session.getCurrentPhase())
                .progressPercentage(session.getProgressPercentage())
                .draftPlan(draftPlan)
                .finalizedBrief(session.getFinalizedBriefJson() == null ? null : objectMapper.convertValue(session.getFinalizedBriefJson(), FinalizedBriefDto.class))
                .finalDocument(session.getFinalDocument())
                .errorMessage(session.getErrorMessage())
                .build();
    }

    @Transactional
    public void deleteGenerationSession(Long sessionId) {
        GenerationSession session = generationSessionRepository.findByIdWithLecture(sessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));
        User currentUser = currentUserResolver.getUser();
        AuthorizationUtil.requireLectureOwner(currentUser, session.getLecture());

        cacheService.delete("shared:draft_plan:" + sessionId);
        cacheService.delete("shared:finalized_brief:" + sessionId);
        generationSessionRepository.delete(session);
    }

    /**
     * Phase 3: 콘텐츠 생성 (챕터 분해 및 본문 집필)
     */
    @Transactional
    public MaterialGenerationPhase3ResponseDto processPhase3(MaterialGenerationPhase3RequestDto requestDto) {
        log.info("Phase 3 처리: sessionId={}", requestDto.getSessionId());
        GenerationSession session = generationSessionRepository.findByIdWithLecture(requestDto.getSessionId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        User currentUser = currentUserResolver.getUser();
        if (!session.getUser().getId().equals(currentUser.getId())) throw new BusinessException(CommonErrorCode.FORBIDDEN);

        if (session.getCurrentPhase() != GenerationPhase.PHASE2) {
            throw new BusinessException(CommonErrorCode.INVALID_PHASE, "Phase 3는 Phase 2가 완료된 상태에서만 가능합니다.");
        }

        FinalizedBriefDto finalizedBrief = objectMapper.convertValue(session.getFinalizedBriefJson(), FinalizedBriefDto.class);

        ChapterContentListDto chapterContentList;
        try {
            chapterContentList = decompositionAgent.decomposeChapters(finalizedBrief, null);
            chapterContentList = writeAgent.writeContent(chapterContentList, null);
        } catch (Exception e) {
            log.error("Phase 3 실패: sessionId={}, error={}", requestDto.getSessionId(), e.getMessage());
            sessionRecoveryService.handleGenerationSessionFailure(requestDto.getSessionId(), GenerationPhase.PHASE3, "Phase 3 실패: " + e.getMessage());
            throw new BusinessException(CommonErrorCode.AGENT_EXECUTION_FAILED, "콘텐츠 생성에 실패했습니다: " + e.getMessage());
        }

        session.updateChapterContentList(objectMapper.convertValue(chapterContentList, Map.class));
        session.updatePhase(GenerationPhase.PHASE3);
        session.updateProgress(60);
        generationSessionRepository.save(session);

        return MaterialGenerationPhase3ResponseDto.builder()
                .sessionId(session.getId())
                .chapterContentList(chapterContentList)
                .progressPercentage(60)
                .message("Phase 3 완료: 콘텐츠 생성이 완료되었습니다.")
                .build();
    }

    /**
     * Phase 4: 검증 및 수정
     */
    @Transactional
    public MaterialGenerationPhase4ResponseDto processPhase4(MaterialGenerationPhase4RequestDto requestDto) {
        log.info("Phase 4 처리: sessionId={}", requestDto.getSessionId());
        GenerationSession session = generationSessionRepository.findByIdWithLecture(requestDto.getSessionId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        if (session.getCurrentPhase() != GenerationPhase.PHASE3) throw new BusinessException(CommonErrorCode.INVALID_PHASE, "Phase 4는 Phase 3 완료 후 가능합니다.");

        ChapterContentListDto chapterContentList = objectMapper.convertValue(session.getChapterContentListJson(), ChapterContentListDto.class);

        VerifiedContentDto verifiedContent;
        try {
            validationAgent.validateContent(chapterContentList);
            verifiedContent = reviewAgent.reviewContent(chapterContentList);
        } catch (Exception e) {
            log.error("Phase 4 실패: sessionId={}, error={}", requestDto.getSessionId(), e.getMessage());
            sessionRecoveryService.handleGenerationSessionFailure(requestDto.getSessionId(), GenerationPhase.PHASE4, "Phase 4 실패: " + e.getMessage());
            throw new BusinessException(CommonErrorCode.AGENT_EXECUTION_FAILED, "검증에 실패했습니다: " + e.getMessage());
        }

        session.updateVerifiedContent(objectMapper.convertValue(verifiedContent, Map.class));
        session.updatePhase(GenerationPhase.PHASE4);
        session.updateProgress(80);
        generationSessionRepository.save(session);

        return MaterialGenerationPhase4ResponseDto.builder()
                .sessionId(session.getId())
                .verifiedContent(verifiedContent)
                .progressPercentage(80)
                .message("Phase 4 완료: 검증 및 리뷰가 완료되었습니다.")
                .build();
    }

    /**
     * Phase 5: 최종 조립
     */
    @Transactional
    public MaterialGenerationPhase5ResponseDto processPhase5(MaterialGenerationPhase5RequestDto requestDto) {
        log.info("Phase 5 처리: sessionId={}", requestDto.getSessionId());
        GenerationSession session = generationSessionRepository.findByIdWithLecture(requestDto.getSessionId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        if (session.getCurrentPhase() != GenerationPhase.PHASE4) throw new BusinessException(CommonErrorCode.INVALID_PHASE, "Phase 5는 Phase 4 완료 후 가능합니다.");

        VerifiedContentDto verifiedContent = objectMapper.convertValue(session.getVerifiedContentJson(), VerifiedContentDto.class);

        String finalDocument;
        try {
            finalDocument = editorAgent.assembleFinalDocument(verifiedContent);
        } catch (Exception e) {
            log.error("Phase 5 실패: sessionId={}, error={}", requestDto.getSessionId(), e.getMessage());
            sessionRecoveryService.handleGenerationSessionFailure(requestDto.getSessionId(), GenerationPhase.PHASE5, "Phase 5 실패: " + e.getMessage());
            throw new BusinessException(CommonErrorCode.AGENT_EXECUTION_FAILED, "문서 조립에 실패했습니다: " + e.getMessage());
        }

        session.updateFinalDocument(finalDocument);
        session.updateProgress(100);
        generationSessionRepository.save(session);

        return MaterialGenerationPhase5ResponseDto.builder()
                .sessionId(session.getId())
                .finalDocument(finalDocument)
                .progressPercentage(100)
                .message("Phase 5 완료: 최종 문서가 생성되었습니다.")
                .build();
    }

    @Transactional(readOnly = true)
    public String getFinalDocument(Long sessionId) {
        GenerationSession session = generationSessionRepository.findByIdWithLecture(sessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        if (session.getFinalDocument() == null) throw new BusinessException(CommonErrorCode.DATA_NOT_FOUND, "최종 문서가 아직 생성되지 않았습니다.");
        return session.getFinalDocument();
    }

    /**
     * Phase 3-5 비동기 자동 처리
     */
    @org.springframework.scheduling.annotation.Async("materialGenerationExecutor")
    public void processPhase3To5Async(String taskId, Long sessionId) {
        log.info("Phase 3-5 비동기 처리 시작: taskId={}, sessionId={}", taskId, sessionId);

        try {
            GenerationSession session = generationSessionRepository.findByIdWithLecture(sessionId)
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));
            
            Map<String, Object> finalizedBriefMap = session.getFinalizedBriefJson();
            String finalizedBriefCacheKey = "shared:finalized_brief:" + sessionId;
            cacheService.set(finalizedBriefCacheKey, finalizedBriefMap, 86400);

            asyncTaskService.updateTaskStatus(taskId, TaskStatus.PROCESSING, 20, "Phase 3-5 시작: 콘텐츠 생성 중..");
            publishProgress(sessionId, 20, "Phase 3-5 시작: 콘텐츠 생성 중..", "PHASE3");

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("session_id", sessionId);
            requestBody.put("finalized_brief", finalizedBriefMap);
            
            Map<String, Object> response = fastApiNoteGenClient.startPhase3To5Auto(requestBody);
            
            if (response != null && "accepted".equals(response.get("status"))) {
                log.info("FastAPI 작업 수락됨: sessionId={}", sessionId);
                asyncTaskService.updateTaskStatus(taskId, TaskStatus.PROCESSING, 30, "AI가 콘텐츠를 집필하고 있습니다..");
                publishProgress(sessionId, 30, "AI가 콘텐츠를 집필하고 있습니다..", "PHASE3");

                String finalResultKey = "fa:result:" + sessionId;
                int maxAttempts = 240; // 약 20분
                int attempt = 0;
                boolean completed = false;

                while (attempt < maxAttempts && !completed) {
                    Thread.sleep(5000);
                    attempt++;
                    if (attempt % 12 == 1) log.info("FastAPI 결과 대기 중: sessionId={}, attempt={}", sessionId, attempt);

                    String finalMarkdown = redisTemplate.opsForValue().get(finalResultKey);
                    if (finalMarkdown != null && !finalMarkdown.isBlank()) {
                        completed = true;
                        session.updateFinalDocument(finalMarkdown);
                        session.updatePhase(GenerationPhase.PHASE5);
                        session.updateProgress(100);
                        generationSessionRepository.save(session);

                        publishProgress(sessionId, 100, "완료: 최종 문서 생성 완료", "PHASE5");
                        asyncTaskService.updateTaskStatus(taskId, TaskStatus.COMPLETED, 100, "완료되었습니다.", objectMapper.writeValueAsString(Map.of("sessionId", sessionId)));
                    }
                }
                if (!completed) throw new BusinessException(CommonErrorCode.AI_SERVER_TIMEOUT, "AI 서버 응답 시간 초과");
            }
        } catch (Exception e) {
            log.error("비동기 처리 실패: {}", e.getMessage());
            asyncTaskService.updateTaskStatus(taskId, TaskStatus.FAILED, null, "오류 발생: " + e.getMessage());
        }
    }

    private void publishProgress(Long sessionId, int progress, String message, String phase) {
        try {
            String channel = "shared:progress:" + sessionId;
            Map<String, Object> data = new HashMap<>();
            data.put("progress", progress);
            data.put("message", message);
            data.put("phase", phase);
            data.put("timestamp", System.currentTimeMillis());

            redisTemplate.convertAndSend(channel, objectMapper.writeValueAsString(data));
        } catch (Exception e) {
            log.warn("진행 현황 발행 실패: sessionId={}", sessionId);
        }
    }

    /**
     * Phase 5 산출물(최종 문서)만 삭제. 세션은 유지하고 Phase 4 완료 상태로 되돌려 Phase 5 재실행 가능.
     */
    @Transactional
    public void deletePhase5Output(Long sessionId) {
        GenerationSession session = generationSessionRepository.findByIdWithLecture(sessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        User currentUser = currentUserResolver.getUser();
        AuthorizationUtil.requireLectureOwner(currentUser, session.getLecture());

        session.clearFinalDocument();
        generationSessionRepository.save(session);
    }

    /**
     * Phase 3~5 산출물만 삭제하고 Phase 2(확정 기획안) 상태로 되돌림. 기획안은 유지.
     */
    @Transactional
    public void rollbackToPhase2(Long sessionId) {
        GenerationSession session = generationSessionRepository.findByIdWithLecture(sessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        User currentUser = currentUserResolver.getUser();
        AuthorizationUtil.requireLectureOwner(currentUser, session.getLecture());

        session.rollbackToPhase2();
        generationSessionRepository.save(session);
    }
}