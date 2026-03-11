package io.github.uou_capstone.aiplatform.domain.material.generation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.agent.material.ConfirmAgent;
import io.github.uou_capstone.aiplatform.agent.material.DecompositionAgent;
import io.github.uou_capstone.aiplatform.agent.material.EditorAgent;
import io.github.uou_capstone.aiplatform.agent.material.PlanningAgent;
import io.github.uou_capstone.aiplatform.agent.material.ReviewAgent;
import io.github.uou_capstone.aiplatform.agent.material.UpdateAgent;
import io.github.uou_capstone.aiplatform.agent.material.ValidationAgent;
import io.github.uou_capstone.aiplatform.agent.material.WriteAgent;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.LectureRepository;
import io.github.uou_capstone.aiplatform.domain.material.generation.GenerationPhase;
import io.github.uou_capstone.aiplatform.domain.material.generation.GenerationSession;
import io.github.uou_capstone.aiplatform.domain.material.generation.GenerationSessionRepository;
import io.github.uou_capstone.aiplatform.domain.material.generation.dto.*;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.domain.user.repository.UserRepository;
import io.github.uou_capstone.aiplatform.service.AsyncTaskService;
import io.github.uou_capstone.aiplatform.service.CacheService;
import io.github.uou_capstone.aiplatform.service.SessionRecoveryService;
import io.github.uou_capstone.aiplatform.util.AuthorizationUtil;
import io.github.uou_capstone.aiplatform.domain.task.entity.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.BodyInserters;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 강의 자료 생성 서비스
 * Version 2의 5단계 파이프라인을 관리하는 서비스
 * 
 * Phase 1: Analysis & Scope (기획)
 * Phase 2: Interactive Briefing (기획 검토 및 확정)
 * Phase 3: Content Generation (심층 조사 및 집필)
 * Phase 4: Review & Verification (검증 및 수정)
 * Phase 5: Final Assembly (최종 조립)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialGenerationService {

    // ========== 의존성 주입 ==========
    private final PlanningAgent planningAgent;  // Phase 1 Agent
    private final ConfirmAgent confirmAgent;  // Phase 2 Agent
    private final UpdateAgent updateAgent;  // Phase 2 Agent (피드백 처리)
    private final DecompositionAgent decompositionAgent;  // Phase 3 Agent
    private final WriteAgent writeAgent;  // Phase 3 Agent
    private final ValidationAgent validationAgent;  // Phase 4 Agent
    private final ReviewAgent reviewAgent;  // Phase 4 Agent
    private final EditorAgent editorAgent;  // Phase 5 Agent
    private final GenerationSessionRepository generationSessionRepository;
    private final LectureRepository lectureRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;  // JSON 변환용
    private final AsyncTaskService asyncTaskService;  // 비동기 작업 상태 추적
    private final SessionRecoveryService sessionRecoveryService;  // 세션 복구 서비스
    private final WebClient aiServiceWebClient;  // FastAPI 호출용 WebClient
    private final CacheService cacheService;  // Redis 캐싱 서비스
    private final StringRedisTemplate redisTemplate;  // Redis Pub/Sub 발행용

    
    /**
     * Phase 1: 초기 키워드 기반 DraftPlan 생성
     * 
     * 로직 설명:
     * 1. 권한 확인: 현재 로그인한 사용자가 해당 강의의 선생님인지 확인
     * 2. 강의 정보 조회: lectureId로 Lecture 엔티티 조회
     * 3. 세션 생성: GenerationSession 엔티티 생성 (PHASE1 상태로 시작)
     * 4. Agent 호출: PlanningAgent를 통해 DraftPlan 생성 (키워드 기반)
     * 5. 결과 저장: 생성된 DraftPlan을 JSON으로 변환하여 세션에 저장
     * 6. 응답 반환: sessionId와 DraftPlan을 포함한 응답 반환
     * 
     * @param requestDto Phase 1 요청 DTO (lectureId, keyword)
     * @return Phase 1 응답 DTO (sessionId, draftPlan)
     */
    @Transactional
    public MaterialGenerationPhase1ResponseDto startPhase1(MaterialGenerationPhase1RequestDto requestDto) {
        log.info("Phase 1 시작: lectureId={}, keyword={}", requestDto.getLectureId(), requestDto.getKeyword());

        // ========== 1단계: 권한 확인 ==========
        // 현재 로그인한 사용자 정보 조회
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

        // ========== 2단계: 강의 정보 조회 ==========
        // lectureId로 Lecture 엔티티 조회
        Lecture lecture = lectureRepository.findById(requestDto.getLectureId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.LECTURE_NOT_FOUND));

        // ========== 2-1단계: 권한 확인 ==========
        // 현재 사용자가 해당 강의의 소유자인지 확인 (선생님 권한 + 강의 소유권 확인)
        AuthorizationUtil.requireLectureOwner(currentUser, lecture);

        // ========== 3단계: 세션 생성 ==========
        // GenerationSession 엔티티 생성
        // - lecture: 강의 정보
        // - user: 현재 로그인한 사용자 (선생님)
        // - userPrompt: 사용자가 입력한 초기 키워드
        // - currentPhase: PHASE1로 초기화
        // - progressPercentage: 0으로 초기화
        GenerationSession session = GenerationSession.builder()
                .lecture(lecture)
                .user(currentUser)
                .userPrompt(requestDto.getKeyword())
                .build();
        session = generationSessionRepository.save(session);
        log.info("GenerationSession 생성 완료: sessionId={}", session.getId());

        // ========== 4단계: Agent 호출 ==========
        // PlanningAgent를 통해 DraftPlan 생성
        // - keyword: 사용자가 입력한 초기 키워드
        // - 반환값: DraftPlanDto (projectMeta, styleGuide, chapters 포함)
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

        // ========== 6단계: 결과 저장 ==========
        // DraftPlanDto를 JSON으로 변환하여 세션에 저장
        // - objectMapper.convertValue(): DTO를 Map으로 변환
        // - Map을 JSON으로 저장 (MySQL JSON 타입)
        Map<String, Object> draftPlanMap = objectMapper.convertValue(draftPlan, Map.class);
        session.updateDraftPlan(draftPlanMap);
        session.updatePhase(GenerationPhase.PHASE1);
        session.updateProgress(20);  // Phase 1 완료 = 20% 진행
        generationSessionRepository.save(session);

        // ✅ Redis에 DraftPlan 캐싱 (FastAPI와 공유, 24시간 TTL)
        String draftPlanCacheKey = "draft_plan:" + session.getId();
        cacheService.set(draftPlanCacheKey, draftPlan, 86400); // 24시간

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
     * 
     * 로직 설명:
     * 1. 세션 조회: sessionId로 GenerationSession 조회
     * 2. 권한 확인: 현재 사용자가 세션 소유자인지 확인
     * 3. Phase 확인: 현재 Phase가 PHASE1인지 확인
     * 4. DraftPlan 조회: 세션에 저장된 DraftPlan JSON 조회
     * 5. Agent 호출: ConfirmAgent를 통해 사용자 피드백 분석
     * 6. 결과 저장: FinalizedBrief를 JSON으로 변환하여 세션에 저장
     * 7. 응답 반환: sessionId와 FinalizedBrief를 포함한 응답 반환
     * 
     * @param requestDto Phase 2 요청 DTO (sessionId, feedback, confirm)
     * @return Phase 2 응답 DTO (sessionId, finalizedBrief)
     */
    @Transactional
    public MaterialGenerationPhase2ResponseDto processPhase2(MaterialGenerationPhase2RequestDto requestDto) {
        // action은 프론트에서 누락/대문자 등으로 올 수 있어 서버에서 보정한다.
        String normalizedAction = normalizePhase2Action(requestDto.getAction(), requestDto.getFeedback());
        log.info("Phase 2 처리: sessionId={}, action={} (normalized={})",
                requestDto.getSessionId(), requestDto.getAction(), normalizedAction);

        // ========== 1단계: 세션 조회 ==========
        GenerationSession session = generationSessionRepository.findById(requestDto.getSessionId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        // ========== 2단계: 권한 확인 ==========
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        
        if (!session.getUser().getId().equals(currentUser.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // ========== 3단계: Phase 확인 ==========
        if (session.getCurrentPhase() != GenerationPhase.PHASE1) {
            throw new BusinessException(
                    CommonErrorCode.INVALID_PHASE, 
                    "Phase 2는 Phase 1 완료 후에만 진행할 수 있습니다."
            );
        }

        // ========== 4단계: DraftPlan 조회 ==========
        // 세션에 저장된 DraftPlan JSON을 DTO로 변환
        Map<String, Object> draftPlanMap = session.getDraftPlanJson();
        if (draftPlanMap == null) {
            throw new BusinessException(CommonErrorCode.DATA_NOT_FOUND, "DraftPlan이 없습니다.");
        }
        DraftPlanDto draftPlan = objectMapper.convertValue(draftPlanMap, DraftPlanDto.class);

        // ========== 5단계: 사용자 피드백 처리 ==========
        // 사용자가 최종 확정(action="confirm")한 경우
        if ("confirm".equals(normalizedAction)) {
            // DraftPlan을 그대로 FinalizedBrief로 사용
            FinalizedBriefDto finalizedBrief = new FinalizedBriefDto();
            finalizedBrief.setProjectMeta(draftPlan.getProjectMeta());
            finalizedBrief.setStyleGuide(draftPlan.getStyleGuide());
            finalizedBrief.setChapters(draftPlan.getChapters());

            // FinalizedBrief를 JSON으로 변환하여 세션에 저장
            Map<String, Object> finalizedBriefMap = objectMapper.convertValue(finalizedBrief, Map.class);
            session.updateFinalizedBrief(finalizedBriefMap);
            session.updatePhase(GenerationPhase.PHASE2);
            session.updateProgress(40);  // Phase 2 완료 = 40% 진행
            generationSessionRepository.save(session);

            // ✅ Redis에 FinalizedBrief 캐싱 (FastAPI와 공유, 24시간 TTL)
            String finalizedBriefCacheKey = "finalized_brief:" + session.getId();
            cacheService.set(finalizedBriefCacheKey, finalizedBrief, 86400); // 24시간

            return MaterialGenerationPhase2ResponseDto.builder()
                    .sessionId(session.getId())
                    .finalizedBrief(finalizedBrief)
                    .progressPercentage(40)
                    .message("Phase 2 완료: 기획안이 확정되었습니다.")
                    .build();
        } else {
            // 사용자가 수정 요청한 경우
            // UpdateAgent를 통해 DraftPlan 수정
            String feedback = requestDto.getFeedback();
            if (feedback == null || feedback.trim().isEmpty()) {
                throw new BusinessException(
                        CommonErrorCode.INVALID_PARAMETER, 
                        "피드백이 제공되지 않았습니다. 수정 요청 시 feedback 필드는 필수입니다."
                );
            }
            
            // UpdateAgent를 통해 DraftPlan 수정 (FinalizedBrief 반환)
            FinalizedBriefDto finalizedBrief;
            try {
                finalizedBrief = updateAgent.updateDraftPlan(draftPlan, feedback);
                if (finalizedBrief == null) {
                    throw new BusinessException(CommonErrorCode.AGENT_EXECUTION_FAILED, 
                            "기획안 수정 결과가 null입니다.");
                }
            } catch (Exception e) {
                log.error("Phase 2 (Update) 실패: sessionId={}, error={}", requestDto.getSessionId(), e.getMessage(), e);
                sessionRecoveryService.handleGenerationSessionFailure(
                        requestDto.getSessionId(), 
                        GenerationPhase.PHASE2, 
                        "Phase 2 (Update) 실패: " + e.getMessage()
                );
                throw new BusinessException(CommonErrorCode.AGENT_EXECUTION_FAILED, 
                        "기획안 수정에 실패했습니다: " + e.getMessage());
            }
            
            // 수정된 FinalizedBrief를 JSON으로 변환하여 세션에 저장
            Map<String, Object> finalizedBriefMap = objectMapper.convertValue(finalizedBrief, Map.class);
            session.updateFinalizedBrief(finalizedBriefMap);
            session.updatePhase(GenerationPhase.PHASE2);
            session.updateProgress(40);  // Phase 2 완료 = 40% 진행
            generationSessionRepository.save(session);

            // ✅ Redis에 FinalizedBrief 캐싱 (FastAPI와 공유, 24시간 TTL)
            String finalizedBriefCacheKey = "finalized_brief:" + session.getId();
            cacheService.set(finalizedBriefCacheKey, finalizedBrief, 86400); // 24시간
            
            return MaterialGenerationPhase2ResponseDto.builder()
                    .sessionId(session.getId())
                    .finalizedBrief(finalizedBrief)
                    .progressPercentage(40)
                    .message("기획안이 수정 및 확정되었습니다.")
                    .build();
        }
    }

    /**
     * AI 서비스(또는 Gemini) 오류 시 사용자에게 보여줄 메시지로 변환.
     * 503/429, high demand, quota 등은 "잠시 후 재시도" 안내로 통일.
     */
    private String mapAgentErrorToUserMessage(Exception e) {
        String body = null;
        int status = 0;
        if (e instanceof WebClientResponseException wce) {
            status = wce.getStatusCode().value();
            try {
                body = wce.getResponseBodyAsString();
            } catch (Exception ignored) {}
        }
        String combined = (body != null && !body.isBlank()) ? body : e.getMessage();
        if (combined != null) {
            if (combined.contains("high demand") || combined.contains("Please try again later") || combined.contains("UNAVAILABLE")) {
                return "기획안 생성에 실패했습니다. AI 모델이 일시적으로 사용량이 많습니다. 잠시 후 다시 시도해 주세요.";
            }
            if (status == 429 || combined.contains("429") || combined.contains("quota") || combined.contains("RESOURCE_EXHAUSTED")) {
                return "기획안 생성에 실패했습니다. API 사용 한도를 초과했습니다. 잠시 후 다시 시도해 주세요.";
            }
            if (status == 503 || combined.contains("503")) {
                return "기획안 생성에 실패했습니다. AI 서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.";
            }
        }
        return "기획안 생성에 실패했습니다: " + (e.getMessage() != null ? e.getMessage() : "알 수 없는 오류");
    }

    private String normalizePhase2Action(String action, String feedback) {
        String trimmed = action == null ? "" : action.trim();
        if (trimmed.isEmpty()) {
            if (feedback != null && !feedback.trim().isEmpty()) {
                return "feedback";
            }
            return "confirm";
        }
        return trimmed.toLowerCase();
    }

    /**
     * 생성 상태 조회
     * 
     * 로직 설명:
     * 1. 세션 조회: sessionId로 GenerationSession 조회
     * 2. 권한 확인: 현재 사용자가 세션 소유자인지 확인
     * 3. 상태 정보 구성: currentPhase, progressPercentage, errorMessage 등
     * 4. 응답 반환: 상태 정보를 포함한 응답 반환
     * 
     * @param sessionId 세션 ID
     * @return 상태 정보 DTO
     */
    @Transactional(readOnly = true)
    public MaterialGenerationStatusDto getStatus(Long sessionId) {
        // ========== 1단계: 세션 조회 ==========
        GenerationSession session = generationSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        // ========== 2단계: 권한 확인 ==========
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        
        if (!session.getUser().getId().equals(currentUser.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // ========== 3단계: 상태 정보 구성 ==========
        return MaterialGenerationStatusDto.builder()
                .sessionId(session.getId())
                .currentPhase(session.getCurrentPhase())
                .progressPercentage(session.getProgressPercentage())
                .errorMessage(session.getErrorMessage())
                .finalDocument(session.getFinalDocument())
                .build();
    }

    /**
     * 강의(lectureId) 기준으로 현재 사용자(교사)가 마지막으로 진행하던 생성 세션을 조회한다.
     * - 창이 의도치 않게 닫히는 경우 프론트가 sessionId를 잃어버릴 수 있어 재개용으로 제공한다.
     */
    @Transactional(readOnly = true)
    public MaterialGenerationResumeDto findLatestGenerationSessionByLectureId(Long lectureId) {
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

        GenerationSession session = generationSessionRepository
                .findTopByLecture_IdAndUser_IdOrderByCreatedAtDesc(lectureId, currentUser.getId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND, "해당 강의의 생성 세션이 없습니다."));

        AuthorizationUtil.requireLectureOwner(currentUser, session.getLecture());

        DraftPlanDto draftPlan = session.getDraftPlanJson() == null
                ? null
                : objectMapper.convertValue(session.getDraftPlanJson(), DraftPlanDto.class);
        FinalizedBriefDto finalizedBrief = session.getFinalizedBriefJson() == null
                ? null
                : objectMapper.convertValue(session.getFinalizedBriefJson(), FinalizedBriefDto.class);
        ChapterContentListDto chapterContentList = session.getChapterContentListJson() == null
                ? null
                : objectMapper.convertValue(session.getChapterContentListJson(), ChapterContentListDto.class);
        VerifiedContentDto verifiedContent = session.getVerifiedContentJson() == null
                ? null
                : objectMapper.convertValue(session.getVerifiedContentJson(), VerifiedContentDto.class);

        return MaterialGenerationResumeDto.builder()
                .sessionId(session.getId())
                .lectureId(session.getLecture().getId())
                .currentPhase(session.getCurrentPhase())
                .progressPercentage(session.getProgressPercentage())
                .draftPlan(draftPlan)
                .finalizedBrief(finalizedBrief)
                .chapterContentList(chapterContentList)
                .verifiedContent(verifiedContent)
                .finalDocument(session.getFinalDocument())
                .errorMessage(session.getErrorMessage())
                .build();
    }

    /**
     * Phase 3: 콘텐츠 생성 (챕터 분해 및 본문 작성)
     * 
     * 로직 설명:
     * 1. 세션 조회 및 권한 확인
     * 2. Phase 확인 (PHASE2 완료 여부)
     * 3. FinalizedBrief 조회
     * 4. DecompositionAgent 호출: 챕터를 하위 주제로 분해 (키워드/기획안 기반, PDF 미사용)
     * 5. WriteAgent 호출: Markdown 본문 작성
     * 6. 결과 저장: ChapterContentList를 JSON으로 변환하여 세션에 저장
     * 7. 응답 반환
     * 
     * @param requestDto Phase 3 요청 DTO (sessionId)
     * @return Phase 3 응답 DTO (sessionId, chapterContentList)
     */
    @Transactional
    public MaterialGenerationPhase3ResponseDto processPhase3(MaterialGenerationPhase3RequestDto requestDto) {
        log.info("Phase 3 처리: sessionId={}", requestDto.getSessionId());

        // ========== 1단계: 세션 조회 ==========
        GenerationSession session = generationSessionRepository.findById(requestDto.getSessionId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        // ========== 2단계: 권한 확인 ==========
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        
        if (!session.getUser().getId().equals(currentUser.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // ========== 3단계: Phase 확인 ==========
        if (session.getCurrentPhase() != GenerationPhase.PHASE2) {
            throw new BusinessException(
                    CommonErrorCode.INVALID_PHASE, 
                    "Phase 3는 Phase 2 완료 후에만 진행할 수 있습니다."
            );
        }

        // ========== 4단계: FinalizedBrief 조회 ==========
        Map<String, Object> finalizedBriefMap = session.getFinalizedBriefJson();
        if (finalizedBriefMap == null) {
            throw new BusinessException(CommonErrorCode.DATA_NOT_FOUND, "FinalizedBrief가 없습니다.");
        }
        FinalizedBriefDto finalizedBrief = objectMapper.convertValue(finalizedBriefMap, FinalizedBriefDto.class);

        // ========== 5단계: DecompositionAgent 호출 (PDF 없이 키워드/기획안만 사용) ==========
        ChapterContentListDto chapterContentList;
        try {
            chapterContentList = decompositionAgent.decomposeChapters(finalizedBrief, null);
        } catch (Exception e) {
            log.error("Phase 3 (Decomposition) 실패: sessionId={}, error={}", requestDto.getSessionId(), e.getMessage(), e);
            sessionRecoveryService.handleGenerationSessionFailure(
                    requestDto.getSessionId(), 
                    GenerationPhase.PHASE3, 
                    "Phase 3 (Decomposition) 실패: " + e.getMessage()
            );
            throw new BusinessException(CommonErrorCode.AGENT_EXECUTION_FAILED, 
                    "챕터 분해에 실패했습니다: " + e.getMessage());
        }

        // ========== 6단계: WriteAgent 호출 (PDF 없이) ==========
        try {
            chapterContentList = writeAgent.writeContent(chapterContentList, null);
        } catch (Exception e) {
            log.error("Phase 3 (Write) 실패: sessionId={}, error={}", requestDto.getSessionId(), e.getMessage(), e);
            sessionRecoveryService.handleGenerationSessionFailure(
                    requestDto.getSessionId(), 
                    GenerationPhase.PHASE3, 
                    "Phase 3 (Write) 실패: " + e.getMessage()
            );
            throw new BusinessException(CommonErrorCode.AGENT_EXECUTION_FAILED, 
                    "본문 작성에 실패했습니다: " + e.getMessage());
        }

        // ========== 8단계: 결과 저장 ==========
        Map<String, Object> chapterContentListMap = objectMapper.convertValue(chapterContentList, Map.class);
        session.updateChapterContentList(chapterContentListMap);
        session.updatePhase(GenerationPhase.PHASE3);
        session.updateProgress(60);  // Phase 3 완료 = 60% 진행
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
     * 
     * 로직 설명:
     * 1. 세션 조회 및 권한 확인
     * 2. Phase 확인 (PHASE3 완료 여부)
     * 3. ChapterContentList 조회
     * 4. ValidationAgent 호출: 검색 결과 충분성 검증
     * 5. ReviewAgent 호출: 품질 검증
     * 6. 결과 저장: VerifiedContent를 JSON으로 변환하여 세션에 저장
     * 7. 응답 반환
     * 
     * @param requestDto Phase 4 요청 DTO (sessionId)
     * @return Phase 4 응답 DTO (sessionId, verifiedContent)
     */
    @Transactional
    public MaterialGenerationPhase4ResponseDto processPhase4(MaterialGenerationPhase4RequestDto requestDto) {
        log.info("Phase 4 처리: sessionId={}", requestDto.getSessionId());

        // ========== 1단계: 세션 조회 ==========
        GenerationSession session = generationSessionRepository.findById(requestDto.getSessionId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        // ========== 2단계: 권한 확인 ==========
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        
        if (!session.getUser().getId().equals(currentUser.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // ========== 3단계: Phase 확인 ==========
        if (session.getCurrentPhase() != GenerationPhase.PHASE3) {
            throw new BusinessException(
                    CommonErrorCode.INVALID_PHASE, 
                    "Phase 4는 Phase 3 완료 후에만 진행할 수 있습니다."
            );
        }

        // ========== 4단계: ChapterContentList 조회 ==========
        Map<String, Object> chapterContentListMap = session.getChapterContentListJson();
        if (chapterContentListMap == null) {
            throw new BusinessException(CommonErrorCode.DATA_NOT_FOUND, "ChapterContentList가 없습니다.");
        }
        ChapterContentListDto chapterContentList = objectMapper.convertValue(
                chapterContentListMap, 
                ChapterContentListDto.class
        );

        // ========== 5단계: ValidationAgent 호출 ==========
        // 검색 결과 충분성 검증
        Map<String, Object> validationResult;
        try {
            validationResult = validationAgent.validateContent(chapterContentList);
            log.info("검증 결과: {}", validationResult);
        } catch (Exception e) {
            log.error("Phase 4 (Validation) 실패: sessionId={}, error={}", requestDto.getSessionId(), e.getMessage(), e);
            sessionRecoveryService.handleGenerationSessionFailure(
                    requestDto.getSessionId(), 
                    GenerationPhase.PHASE4, 
                    "Phase 4 (Validation) 실패: " + e.getMessage()
            );
            throw new BusinessException(CommonErrorCode.AGENT_EXECUTION_FAILED, 
                    "콘텐츠 검증에 실패했습니다: " + e.getMessage());
        }

        // ========== 6단계: ReviewAgent 호출 ==========
        // 품질 검증
        VerifiedContentDto verifiedContent;
        try {
            verifiedContent = reviewAgent.reviewContent(chapterContentList);
        } catch (Exception e) {
            log.error("Phase 4 (Review) 실패: sessionId={}, error={}", requestDto.getSessionId(), e.getMessage(), e);
            sessionRecoveryService.handleGenerationSessionFailure(
                    requestDto.getSessionId(), 
                    GenerationPhase.PHASE4, 
                    "Phase 4 (Review) 실패: " + e.getMessage()
            );
            throw new BusinessException(CommonErrorCode.AGENT_EXECUTION_FAILED, 
                    "콘텐츠 리뷰에 실패했습니다: " + e.getMessage());
        }

        // ========== 7단계: 결과 저장 ==========
        Map<String, Object> verifiedContentMap = objectMapper.convertValue(verifiedContent, Map.class);
        session.updateVerifiedContent(verifiedContentMap);
        session.updatePhase(GenerationPhase.PHASE4);
        session.updateProgress(80);  // Phase 4 완료 = 80% 진행
        generationSessionRepository.save(session);

        return MaterialGenerationPhase4ResponseDto.builder()
                .sessionId(session.getId())
                .verifiedContent(verifiedContent)
                .progressPercentage(80)
                .message("Phase 4 완료: 콘텐츠 검증 및 수정이 완료되었습니다.")
                .build();
    }

    /**
     * Phase 5: 최종 조립
     * 
     * 로직 설명:
     * 1. 세션 조회 및 권한 확인
     * 2. Phase 확인 (PHASE4 완료 여부)
     * 3. VerifiedContent 조회
     * 4. EditorAgent 호출: 최종 문서 조립
     * 5. 결과 저장: 최종 Markdown 문서를 세션에 저장
     * 6. 응답 반환
     * 
     * @param requestDto Phase 5 요청 DTO (sessionId)
     * @return Phase 5 응답 DTO (sessionId, finalDocument)
     */
    @Transactional
    public MaterialGenerationPhase5ResponseDto processPhase5(MaterialGenerationPhase5RequestDto requestDto) {
        log.info("Phase 5 처리: sessionId={}", requestDto.getSessionId());

        // ========== 1단계: 세션 조회 ==========
        GenerationSession session = generationSessionRepository.findById(requestDto.getSessionId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        // ========== 2단계: 권한 확인 ==========
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        
        if (!session.getUser().getId().equals(currentUser.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // ========== 3단계: Phase 확인 ==========
        if (session.getCurrentPhase() != GenerationPhase.PHASE4) {
            throw new BusinessException(
                    CommonErrorCode.INVALID_PHASE, 
                    "Phase 5는 Phase 4 완료 후에만 진행할 수 있습니다."
            );
        }

        // ========== 4단계: VerifiedContent 조회 ==========
        Map<String, Object> verifiedContentMap = session.getVerifiedContentJson();
        if (verifiedContentMap == null) {
            throw new BusinessException(CommonErrorCode.DATA_NOT_FOUND, "VerifiedContent가 없습니다.");
        }
        VerifiedContentDto verifiedContent = objectMapper.convertValue(
                verifiedContentMap, 
                VerifiedContentDto.class
        );

        // ========== 5단계: EditorAgent 호출 ==========
        // 최종 문서 조립
        String finalDocument;
        try {
            finalDocument = editorAgent.assembleFinalDocument(verifiedContent);
        } catch (Exception e) {
            log.error("Phase 5 실패: sessionId={}, error={}", requestDto.getSessionId(), e.getMessage(), e);
            sessionRecoveryService.handleGenerationSessionFailure(
                    requestDto.getSessionId(), 
                    GenerationPhase.PHASE5, 
                    "Phase 5 실패: " + e.getMessage()
            );
            throw new BusinessException(CommonErrorCode.AGENT_EXECUTION_FAILED, 
                    "최종 문서 조립에 실패했습니다: " + e.getMessage());
        }

        // ========== 6단계: 결과 저장 ==========
        session.updateFinalDocument(finalDocument);
        generationSessionRepository.save(session);

        return MaterialGenerationPhase5ResponseDto.builder()
                .sessionId(session.getId())
                .finalDocument(finalDocument)
                .documentUrl("/api/materials/generation/" + session.getId() + "/document")
                .progressPercentage(100)
                .message("Phase 5 완료: 최종 문서가 생성되었습니다.")
                .build();
    }

    /**
     * 최종 문서 조회
     * 
     * 로직 설명:
     * 1. 세션 조회: sessionId로 GenerationSession 조회
     * 2. 권한 확인: 현재 사용자가 세션 소유자인지 확인
     * 3. 최종 문서 조회: 세션에 저장된 finalDocument 반환
     * 
     * @param sessionId 세션 ID
     * @return 최종 Markdown 문서
     */
    @Transactional(readOnly = true)
    public String getFinalDocument(Long sessionId) {
        // ========== 1단계: 세션 조회 ==========
        GenerationSession session = generationSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        // ========== 2단계: 권한 확인 ==========
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        
        if (!session.getUser().getId().equals(currentUser.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // ========== 3단계: 최종 문서 조회 ==========
        if (session.getFinalDocument() == null) {
            throw new BusinessException(
                    CommonErrorCode.DATA_NOT_FOUND, 
                    "최종 문서가 아직 생성되지 않았습니다. Phase 5를 먼저 완료해주세요."
            );
        }

        return session.getFinalDocument();
    }

    /**
     * Phase 3-5 비동기 처리 (AsyncTaskService 통합)
     * 
     * 로직 설명:
     * 1. 작업 생성: AsyncTaskService에 작업 등록
     * 2. Phase 3 처리: 콘텐츠 생성 (진행률: 0% → 20% → 40% → 60%)
     * 3. Phase 4 처리: 검증 및 수정 (진행률: 60% → 80%)
     * 4. Phase 5 처리: 최종 조립 (진행률: 80% → 100%)
     * 5. 완료 처리: 작업 상태를 COMPLETED로 업데이트
     * 
     * @param taskId 작업 ID
     * @param sessionId 세션 ID
     */
    @org.springframework.scheduling.annotation.Async("materialGenerationExecutor")
    public void processPhase3To5Async(String taskId, Long sessionId) {
        try {
            // ========== 1단계: 세션 조회 및 FinalizedBrief 확인 ==========
            GenerationSession session = generationSessionRepository.findById(sessionId)
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));
            
            Map<String, Object> finalizedBriefMap = session.getFinalizedBriefJson();
            if (finalizedBriefMap == null) {
                throw new BusinessException(CommonErrorCode.DATA_NOT_FOUND, "FinalizedBrief가 없습니다.");
            }
            
            // ========== 2단계: ko 브랜치 통합 엔드포인트 호출 ==========
            // ai-service(FastAPI) POST /api/lecture-gen/phase3-5/auto — FE는 호출하지 않음. Spring이 async 처리 시 내부 호출.
            asyncTaskService.updateTaskStatus(taskId, TaskStatus.PROCESSING, 20, "Phase 3-5 시작: 콘텐츠 생성 중...");
            
            // ✅ Redis Pub/Sub으로 진행 상황 발행
            publishProgress(sessionId, 20, "Phase 3-5 시작: 콘텐츠 생성 중...", "PHASE3");
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("session_id", sessionId);
            requestBody.put("finalized_brief", finalizedBriefMap);
            
            // FastAPI 통합 엔드포인트 호출
            Map<String, Object> response;
            try {
                response = aiServiceWebClient.post()
                        .uri("/api/lecture-gen/phase3-5/auto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(BodyInserters.fromValue(requestBody))
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block();
            } catch (WebClientResponseException wce) {
                // 400/500 등 FastAPI가 내려준 상세 에러를 그대로 남겨서 원인 파악이 가능하게 한다.
                String body = null;
                try {
                    body = wce.getResponseBodyAsString();
                } catch (Exception ignored) {}
                String detail = (body != null && !body.isBlank()) ? body : wce.getMessage();
                String msg = "FastAPI /api/lecture-gen/phase3-5/auto 호출 실패 (status=" + wce.getStatusCode().value() + "): " + detail;
                log.error(msg, wce);

                // ✅ Redis Pub/Sub으로 실패 진행 상황 발행
                publishProgress(sessionId, 0, msg, "ERROR");
                // Task에도 상세 메시지 저장
                asyncTaskService.updateTaskStatus(taskId, TaskStatus.FAILED, null, msg);
                throw new BusinessException(CommonErrorCode.AI_SERVER_ERROR, msg);
            }
            
            if (response == null || !response.containsKey("task_id")) {
                throw new BusinessException(CommonErrorCode.AI_SERVER_ERROR, "FastAPI 작업 등록 실패");
            }
            
            String fastApiTaskId = (String) response.get("task_id");
            String statusUrl = (String) response.get("status_url");
            
            log.info("FastAPI Phase 3-5 작업 등록 완료: fastApiTaskId={}, statusUrl={}", fastApiTaskId, statusUrl);
            
            // ========== 3단계: FastAPI 작업 상태 폴링 ==========
            // FastAPI의 /api/lecture-gen/status/{task_id}로 진행 상황 확인
            asyncTaskService.updateTaskStatus(taskId, TaskStatus.PROCESSING, 30, "FastAPI에서 콘텐츠 생성 중...");
            
            // ✅ Redis Pub/Sub으로 진행 상황 발행
            publishProgress(sessionId, 30, "FastAPI에서 콘텐츠 생성 중...", "PHASE3");
            
            // 폴링 로직 (최대 20분 대기, 5초마다 확인)
            int maxAttempts = 240; // 20분 = 1200초 / 5초 = 240회
            int attempt = 0;
            boolean completed = false;
            
            while (attempt < maxAttempts && !completed) {
                try {
                    Thread.sleep(5000); // 5초 대기
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, "작업이 중단되었습니다.");
                }
                attempt++;
                
                try {
                    Map<String, Object> statusResponse = aiServiceWebClient.get()
                            .uri(statusUrl)
                            .retrieve()
                            .bodyToMono(Map.class)
                            .block();
                    
                    if (statusResponse != null) {
                        String status = (String) statusResponse.get("status");
                        Integer progress = statusResponse.get("progress") != null 
                            ? ((Number) statusResponse.get("progress")).intValue() 
                            : 0;
                        String message = (String) statusResponse.get("message");
                        
                        // 백엔드 task 진행률 업데이트 (30% ~ 90%)
                        int backendProgress = 30 + (progress * 60 / 100); // FastAPI 진행률을 30-90% 범위로 매핑
                        asyncTaskService.updateTaskStatus(taskId, TaskStatus.PROCESSING, backendProgress, message);
                        
                        // ✅ Redis Pub/Sub으로 진행 상황 발행
                        publishProgress(sessionId, backendProgress, message, "PHASE3-5");
                        
                        if ("completed".equals(status)) {
                            completed = true;
                            
                            // 최종 결과 저장
                            String finalMarkdown = (String) statusResponse.get("result");
                            if (finalMarkdown != null) {
                                session.updateFinalDocument(finalMarkdown);
                                session.updatePhase(GenerationPhase.PHASE5);
                                session.updateProgress(100);
                                generationSessionRepository.save(session);
                            }
                            
                            // ✅ 완료 진행 상황 발행
                            publishProgress(sessionId, 100, "완료: 최종 문서 생성 완료", "PHASE5");
                            
                            // 완료 처리
                            String resultJson = objectMapper.writeValueAsString(Map.of(
                                "sessionId", sessionId,
                                "message", "강의 자료 생성이 완료되었습니다.",
                                "fastApiTaskId", fastApiTaskId
                            ));
                            asyncTaskService.updateTaskStatus(taskId, TaskStatus.COMPLETED, 100, "완료: 최종 문서 생성 완료", resultJson);
                            
                        } else if ("failed".equals(status)) {
                            String errorMessage = (String) statusResponse.get("error");
                            
                            // ✅ 실패 진행 상황 발행
                            publishProgress(sessionId, backendProgress, "오류 발생: " + errorMessage, "ERROR");
                            
                            throw new BusinessException(CommonErrorCode.AI_SERVER_ERROR, 
                                "FastAPI 작업 실패: " + (errorMessage != null ? errorMessage : "알 수 없는 오류"));
                        }
                    }
                } catch (Exception e) {
                    log.warn("FastAPI 상태 조회 실패 (시도 {}/{}): {}", attempt, maxAttempts, e.getMessage());
                    // 계속 재시도
                }
            }
            
            if (!completed) {
                throw new BusinessException(CommonErrorCode.AI_SERVER_TIMEOUT, 
                    "FastAPI 작업이 시간 초과되었습니다. (최대 대기 시간: 20분)");
            }
            
        } catch (BusinessException e) {
            log.error("Phase 3-5 비동기 처리 실패: taskId={}, sessionId={}, error={}", taskId, sessionId, e.getMessage());
            asyncTaskService.updateTaskStatus(
                taskId, 
                TaskStatus.FAILED, 
                null, 
                "오류 발생: " + e.getMessage()
            );
        } catch (Exception e) {
            log.error("Phase 3-5 비동기 처리 실패: taskId={}, sessionId={}", taskId, sessionId, e);
            asyncTaskService.updateTaskStatus(
                taskId, 
                TaskStatus.FAILED, 
                null, 
                "오류 발생: " + e.getMessage()
            );
        }
    }

    /**
     * Redis Pub/Sub으로 진행 상황 발행
     * 
     * @param sessionId 세션 ID
     * @param progress 진행률 (0-100)
     * @param message 진행 상황 메시지
     * @param phase 현재 Phase
     */
    private void publishProgress(Long sessionId, int progress, String message, String phase) {
        try {
            String channel = "progress:session:" + sessionId;
            Map<String, Object> progressData = new HashMap<>();
            progressData.put("progress", progress);
            progressData.put("message", message);
            progressData.put("phase", phase);
            progressData.put("timestamp", System.currentTimeMillis());
            
            String progressJson = objectMapper.writeValueAsString(progressData);
            redisTemplate.convertAndSend(channel, progressJson);
            
            log.debug("진행 상황 발행: sessionId={}, progress={}%, message={}", sessionId, progress, message);
        } catch (Exception e) {
            log.warn("진행 상황 발행 실패: sessionId={}", sessionId, e);
            // 발행 실패해도 작업은 계속 진행
        }
    }
}
