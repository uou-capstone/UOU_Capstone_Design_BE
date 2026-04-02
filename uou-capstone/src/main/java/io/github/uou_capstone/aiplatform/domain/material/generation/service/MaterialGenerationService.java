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
import io.github.uou_capstone.aiplatform.integration.fastapi.FastApiNoteGenClient;
import io.github.uou_capstone.aiplatform.service.AsyncTaskService;
import io.github.uou_capstone.aiplatform.service.CacheService;
import io.github.uou_capstone.aiplatform.service.SessionRecoveryService;
import io.github.uou_capstone.aiplatform.util.AuthorizationUtil;
import io.github.uou_capstone.aiplatform.domain.task.entity.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 강의 ?�료 ?�성 ?�비??
 * Version 2??5?�계 ?�이?�라?�을 관리하???�비??
 * 
 * Phase 1: Analysis & Scope (기획)
 * Phase 2: Interactive Briefing (기획 검??�??�정)
 * Phase 3: Content Generation (?�층 조사 �?집필)
 * Phase 4: Review & Verification (검�?�??�정)
 * Phase 5: Final Assembly (최종 조립)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialGenerationService {

    // ========== ?�존??주입 ==========
    private final PlanningAgent planningAgent;  // Phase 1 Agent
    private final ConfirmAgent confirmAgent;  // Phase 2 Agent
    private final UpdateAgent updateAgent;  // Phase 2 Agent (?�드�?처리)
    private final DecompositionAgent decompositionAgent;  // Phase 3 Agent
    private final WriteAgent writeAgent;  // Phase 3 Agent
    private final ValidationAgent validationAgent;  // Phase 4 Agent
    private final ReviewAgent reviewAgent;  // Phase 4 Agent
    private final EditorAgent editorAgent;  // Phase 5 Agent
    private final GenerationSessionRepository generationSessionRepository;
    private final LectureRepository lectureRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;  // JSON 변?�용
    private final AsyncTaskService asyncTaskService;  // 비동�??�업 ?�태 추적
    private final SessionRecoveryService sessionRecoveryService;  // ?�션 복구 ?�비??
    private final FastApiNoteGenClient fastApiNoteGenClient;
    private final CacheService cacheService;  // Redis 캐싱 ?�비??
    private final StringRedisTemplate redisTemplate;  // Redis Pub/Sub 발행??

    
    /**
     * Phase 1: 초기 ?�워??기반 DraftPlan ?�성
     * 
     * 로직 ?�명:
     * 1. 권한 ?�인: ?�재 로그?�한 ?�용?��? ?�당 강의???�생?�인지 ?�인
     * 2. 강의 ?�보 조회: lectureId�?Lecture ?�티??조회
     * 3. ?�션 ?�성: GenerationSession ?�티???�성 (PHASE1 ?�태�??�작)
     * 4. Agent ?�출: PlanningAgent�??�해 DraftPlan ?�성 (?�워??기반)
     * 5. 결과 ?�?? ?�성??DraftPlan??JSON?�로 변?�하???�션???�??
     * 6. ?�답 반환: sessionId?� DraftPlan???�함???�답 반환
     * 
     * @param requestDto Phase 1 ?�청 DTO (lectureId, keyword)
     * @return Phase 1 ?�답 DTO (sessionId, draftPlan)
     */
    @Transactional
    public MaterialGenerationPhase1ResponseDto startPhase1(MaterialGenerationPhase1RequestDto requestDto) {
        log.info("Phase 1 ?�작: lectureId={}, keyword={}", requestDto.getLectureId(), requestDto.getKeyword());

        // ========== 1?�계: 권한 ?�인 ==========
        // ?�재 로그?�한 ?�용???�보 조회
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

        // ========== 2?�계: 강의 ?�보 조회 ==========
        // lectureId�?Lecture ?�티??조회
        Lecture lecture = lectureRepository.findById(requestDto.getLectureId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.LECTURE_NOT_FOUND));

        // ========== 2-1?�계: 권한 ?�인 ==========
        // ?�재 ?�용?��? ?�당 강의???�유?�인지 ?�인 (?�생??권한 + 강의 ?�유�??�인)
        AuthorizationUtil.requireLectureOwner(currentUser, lecture);

        // ========== 3?�계: ?�션 ?�성 ==========
        // GenerationSession ?�티???�성
        // - lecture: 강의 ?�보
        // - user: ?�재 로그?�한 ?�용??(?�생??
        // - userPrompt: ?�용?��? ?�력??초기 ?�워??
        // - currentPhase: PHASE1�?초기??
        // - progressPercentage: 0?�로 초기??
        GenerationSession session = GenerationSession.builder()
                .lecture(lecture)
                .user(currentUser)
                .userPrompt(requestDto.getKeyword())
                .build();
        session = generationSessionRepository.save(session);
        log.info("GenerationSession ?�성 ?�료: sessionId={}", session.getId());

        // ========== 4?�계: Agent ?�출 ==========
        // PlanningAgent�??�해 DraftPlan ?�성
        // - keyword: ?�용?��? ?�력??초기 ?�워??
        // - 반환�? DraftPlanDto (projectMeta, styleGuide, chapters ?�함)
        DraftPlanDto draftPlan;
        try {
            draftPlan = planningAgent.generateDraftPlan(requestDto.getKeyword());
            log.info("PlanningAgent ?�출 ?�료. DraftPlan ?�성.");
        } catch (Exception e) {
            log.error("Phase 1 ?�패: sessionId={}, error={}", session.getId(), e.getMessage(), e);
            String failureDetail = "Phase 1 ?�패: " + e.getMessage();
            sessionRecoveryService.handleGenerationSessionFailure(
                    session.getId(), 
                    GenerationPhase.PHASE1, 
                    failureDetail
            );
            String userMessage = mapAgentErrorToUserMessage(e);
            throw new BusinessException(CommonErrorCode.AGENT_EXECUTION_FAILED, userMessage);
        }

        // ========== 5.5?�계: 챕터 order/estimated_sections 기본�?채우�?(?�론???�시·?�렬?? ==========
        normalizeDraftPlanChapters(draftPlan);

        // ========== 6?�계: 결과 ?�??==========
        // DraftPlanDto�?JSON?�로 변?�하???�션???�??
        // - objectMapper.convertValue(): DTO�?Map?�로 변??
        // - Map??JSON?�로 ?�??(MySQL JSON ?�??
        Map<String, Object> draftPlanMap = objectMapper.convertValue(draftPlan, Map.class);
        session.updateDraftPlan(draftPlanMap);
        session.updatePhase(GenerationPhase.PHASE1);
        session.updateProgress(20);  // Phase 1 ?�료 = 20% 진행
        generationSessionRepository.save(session);

        // ??Redis??DraftPlan 캐싱 (FastAPI?� 공유, 24?�간 TTL)
        String draftPlanCacheKey = "shared:draft_plan:" + session.getId();
        cacheService.set(draftPlanCacheKey, draftPlan, 86400); // 24?�간

        // ========== 7?�계: ?�답 반환 ==========
        return MaterialGenerationPhase1ResponseDto.builder()
                .sessionId(session.getId())
                .draftPlan(draftPlan)
                .progressPercentage(20)
                .message("Phase 1 ?�료: 기획??초안???�성?�었?�니??")
                .build();
    }

    /**
     * Phase 2: ?�용???�드�?기반 FinalizedBrief ?�성
     * 
     * 로직 ?�명:
     * 1. ?�션 조회: sessionId�?GenerationSession 조회
     * 2. 권한 ?�인: ?�재 ?�용?��? ?�션 ?�유?�인지 ?�인
     * 3. Phase ?�인: ?�재 Phase가 PHASE1?��? ?�인
     * 4. DraftPlan 조회: ?�션???�?�된 DraftPlan JSON 조회
     * 5. Agent ?�출: ConfirmAgent�??�해 ?�용???�드�?분석
     * 6. 결과 ?�?? FinalizedBrief�?JSON?�로 변?�하???�션???�??
     * 7. ?�답 반환: sessionId?� FinalizedBrief�??�함???�답 반환
     * 
     * @param requestDto Phase 2 ?�청 DTO (sessionId, feedback, confirm)
     * @return Phase 2 ?�답 DTO (sessionId, finalizedBrief)
     */
    @Transactional
    public MaterialGenerationPhase2ResponseDto processPhase2(MaterialGenerationPhase2RequestDto requestDto) {
        // action?� ?�론?�에???�락/?�문자 ?�으�??????�어 ?�버?�서 보정?�다.
        String normalizedAction = normalizePhase2Action(requestDto.getAction(), requestDto.getFeedback());
        log.info("Phase 2 처리: sessionId={}, action={} (normalized={})",
                requestDto.getSessionId(), requestDto.getAction(), normalizedAction);

        // ========== 1?�계: ?�션 조회 ==========
        GenerationSession session = generationSessionRepository.findByIdWithLecture(requestDto.getSessionId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        // ========== 2?�계: 권한 ?�인 ==========
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        
        if (!session.getUser().getId().equals(currentUser.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // ========== 3?�계: Phase ?�인 ==========
        // confirm: PHASE1�??�용. feedback(?�정 ?�청): PHASE1 ?�는 PHASE2(rollback ?? ?�용
        GenerationPhase currentPhase = session.getCurrentPhase();
        if ("confirm".equals(normalizedAction)) {
            if (currentPhase != GenerationPhase.PHASE1) {
                throw new BusinessException(
                        CommonErrorCode.INVALID_PHASE,
                        "?��? 기획?�이 ?�정???�태?�니?? ?�정???�요?�면 ?�정 ?�항???�력??주세??"
                );
            }
        } else {
            if (currentPhase != GenerationPhase.PHASE1 && currentPhase != GenerationPhase.PHASE2) {
                throw new BusinessException(
                        CommonErrorCode.INVALID_PHASE,
                        "기획???�정?� Phase 1 ?�는 Phase 2(?�돌리기 ?? ?�태?�서�?가?�합?�다."
                );
            }
        }

        // ========== 4?�계: ?�정 기�? 기획??조회 ==========
        // PHASE1: DraftPlan ?�용. PHASE2(rollback ??: ?�정 기획??FinalizedBrief)??기�??�로 ?�정 (?�일 ?�키�?
        DraftPlanDto draftPlan;
        if (currentPhase == GenerationPhase.PHASE1) {
            Map<String, Object> draftPlanMap = session.getDraftPlanJson();
            if (draftPlanMap == null) {
                throw new BusinessException(CommonErrorCode.DATA_NOT_FOUND, "DraftPlan???�습?�다.");
            }
            draftPlan = objectMapper.convertValue(draftPlanMap, DraftPlanDto.class);
        } else {
            Map<String, Object> finalizedBriefMap = session.getFinalizedBriefJson();
            if (finalizedBriefMap == null) {
                throw new BusinessException(CommonErrorCode.DATA_NOT_FOUND, "?�정 기획?�이 ?�습?�다.");
            }
            draftPlan = objectMapper.convertValue(finalizedBriefMap, DraftPlanDto.class);
        }

        // ========== 5?�계: ?�용???�드�?처리 ==========
        // ?�용?��? 최종 ?�정(action="confirm")??경우
        if ("confirm".equals(normalizedAction)) {
            // DraftPlan??그�?�?FinalizedBrief�??�용
            FinalizedBriefDto finalizedBrief = new FinalizedBriefDto();
            finalizedBrief.setProjectMeta(draftPlan.getProjectMeta());
            finalizedBrief.setStyleGuide(draftPlan.getStyleGuide());
            finalizedBrief.setChapters(draftPlan.getChapters());

            // FinalizedBrief�?JSON?�로 변?�하???�션???�??
            Map<String, Object> finalizedBriefMap = objectMapper.convertValue(finalizedBrief, Map.class);
            session.updateFinalizedBrief(finalizedBriefMap);
            session.updatePhase(GenerationPhase.PHASE2);
            session.updateProgress(40);  // Phase 2 ?�료 = 40% 진행
            generationSessionRepository.save(session);

            // ??Redis??FinalizedBrief 캐싱 (FastAPI?� 공유, 24?�간 TTL)
            String finalizedBriefCacheKey = "shared:finalized_brief:" + session.getId();
            cacheService.set(finalizedBriefCacheKey, finalizedBrief, 86400); // 24?�간

            return MaterialGenerationPhase2ResponseDto.builder()
                    .sessionId(session.getId())
                    .finalizedBrief(finalizedBrief)
                    .progressPercentage(40)
                    .message("Phase 2 ?�료: 기획?�이 ?�정?�었?�니??")
                    .build();
        } else {
            // ?�용?��? ?�정 ?�청??경우
            // UpdateAgent�??�해 DraftPlan ?�정
            String feedback = requestDto.getFeedback();
            if (feedback == null || feedback.trim().isEmpty()) {
                throw new BusinessException(
                        CommonErrorCode.INVALID_PARAMETER, 
                        "?�드백이 ?�공?��? ?�았?�니?? ?�정 ?�청 ??feedback ?�드???�수?�니??"
                );
            }
            
            // UpdateAgent�??�해 DraftPlan ?�정 (FinalizedBrief 반환)
            FinalizedBriefDto finalizedBrief;
            try {
                finalizedBrief = updateAgent.updateDraftPlan(draftPlan, feedback);
                if (finalizedBrief == null) {
                    throw new BusinessException(CommonErrorCode.AGENT_EXECUTION_FAILED, 
                            "기획???�정 결과가 null?�니??");
                }
            } catch (Exception e) {
                log.error("Phase 2 (Update) ?�패: sessionId={}, error={}", requestDto.getSessionId(), e.getMessage(), e);
                sessionRecoveryService.handleGenerationSessionFailure(
                        requestDto.getSessionId(), 
                        GenerationPhase.PHASE2, 
                        "Phase 2 (Update) ?�패: " + e.getMessage()
                );
                throw new BusinessException(CommonErrorCode.AGENT_EXECUTION_FAILED, 
                        "기획???�정???�패?�습?�다: " + e.getMessage());
            }
            
            // ?�정??FinalizedBrief�?JSON?�로 변?�하???�션???�??
            Map<String, Object> finalizedBriefMap = objectMapper.convertValue(finalizedBrief, Map.class);
            session.updateFinalizedBrief(finalizedBriefMap);
            session.updatePhase(GenerationPhase.PHASE2);
            session.updateProgress(40);  // Phase 2 ?�료 = 40% 진행
            generationSessionRepository.save(session);

            // ??Redis??FinalizedBrief 캐싱 (FastAPI?� 공유, 24?�간 TTL)
            String finalizedBriefCacheKey = "shared:finalized_brief:" + session.getId();
            cacheService.set(finalizedBriefCacheKey, finalizedBrief, 86400); // 24?�간
            
            return MaterialGenerationPhase2ResponseDto.builder()
                    .sessionId(session.getId())
                    .finalizedBrief(finalizedBrief)
                    .progressPercentage(40)
                    .message("기획?�이 ?�정 �??�정?�었?�니??")
                    .build();
        }
    }

    /**
     * AI ?�비???�는 Gemini) ?�류 ???�용?�에�?보여�?메시지�?변??
     * 503/429, high demand, quota ?��? "?�시 ???�시?? ?�내�??�일.
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
                return "기획???�성???�패?�습?�다. AI 모델???�시?�으�??�용?�이 많습?�다. ?�시 ???�시 ?�도??주세??";
            }
            if (status == 429 || combined.contains("429") || combined.contains("quota") || combined.contains("RESOURCE_EXHAUSTED")) {
                return "기획???�성???�패?�습?�다. API ?�용 ?�도�?초과?�습?�다. ?�시 ???�시 ?�도??주세??";
            }
            if (status == 503 || combined.contains("503")) {
                return "기획???�성???�패?�습?�다. AI ?�비?��? ?�시?�으�??�용?????�습?�다. ?�시 ???�시 ?�도??주세??";
            }
        }
        return "기획???�성???�패?�습?�다: " + (e.getMessage() != null ? e.getMessage() : "?????�는 ?�류");
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
     * draftPlan.chapters[]??order, estimated_sections가 null????기본�?채�?.
     * ?�론?�에???�시/?�렬 ??null???�니?�록 보장.
     */
    private void normalizeDraftPlanChapters(DraftPlanDto draftPlan) {
        if (draftPlan == null || draftPlan.getChapters() == null) return;
        List<ChapterDto> chapters = draftPlan.getChapters();
        for (int i = 0; i < chapters.size(); i++) {
            ChapterDto ch = chapters.get(i);
            if (ch.getOrder() == null) ch.setOrder(i + 1);
            if (ch.getEstimatedSections() == null) ch.setEstimatedSections(1);
        }
    }

    /**
     * ?�성 ?�태 조회
     * 
     * 로직 ?�명:
     * 1. ?�션 조회: sessionId�?GenerationSession 조회
     * 2. 권한 ?�인: ?�재 ?�용?��? ?�션 ?�유?�인지 ?�인
     * 3. ?�태 ?�보 구성: currentPhase, progressPercentage, errorMessage ??
     * 4. ?�답 반환: ?�태 ?�보�??�함???�답 반환
     * 
     * @param sessionId ?�션 ID
     * @return ?�태 ?�보 DTO
     */
    @Transactional(readOnly = true)
    public MaterialGenerationStatusDto getStatus(Long sessionId) {
        // ========== 1?�계: ?�션 조회 ==========
        GenerationSession session = generationSessionRepository.findByIdWithLecture(sessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        // ========== 2?�계: 권한 ?�인 ==========
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        
        if (!session.getUser().getId().equals(currentUser.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // ========== 3?�계: ?�태 ?�보 구성 ==========
        return MaterialGenerationStatusDto.builder()
                .sessionId(session.getId())
                .currentPhase(session.getCurrentPhase())
                .progressPercentage(session.getProgressPercentage())
                .errorMessage(session.getErrorMessage())
                .finalDocument(session.getFinalDocument())
                .build();
    }

    /**
     * 강의(lectureId) 기�??�로 ?�재 ?�용??교사)가 마�?막으�?진행?�던 ?�성 ?�션??조회?�다.
     * - 창이 ?�도�??�게 ?�히??경우 ?�론?��? sessionId�??�어버릴 ???�어 ?�개?�으�??�공?�다.
     */
    @Transactional(readOnly = true)
    public MaterialGenerationResumeDto findLatestGenerationSessionByLectureId(Long lectureId) {
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

        GenerationSession session = generationSessionRepository
                .findByLectureAndUserWithLecture(lectureId, currentUser.getId())
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND, "해당 강의의 생성 세션이 없습니다."));

        AuthorizationUtil.requireLectureOwner(currentUser, session.getLecture());

        DraftPlanDto draftPlan = session.getDraftPlanJson() == null
                ? null
                : objectMapper.convertValue(session.getDraftPlanJson(), DraftPlanDto.class);
        if (draftPlan != null) {
            normalizeDraftPlanChapters(draftPlan);
        }
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
     * 강의 ?�료 ?�성 ?�션 ?�체 ??�� (Phase 1~5). 기획?�·확?�안·챕터·검증·최종문????모든 ?�출�??�거.
     * ?�당 강의 ?�유(교사)�???�� 가?? Redis 캐시(shared:draft_plan, shared:finalized_brief)???�께 ?�거.
     */
    @Transactional
    public void deleteGenerationSession(Long sessionId) {
        GenerationSession session = generationSessionRepository.findByIdWithLecture(sessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        AuthorizationUtil.requireLectureOwner(currentUser, session.getLecture());

        cacheService.delete("shared:draft_plan:" + sessionId);
        cacheService.delete("shared:finalized_brief:" + sessionId);
        generationSessionRepository.delete(session);
        generationSessionRepository.flush();
    }

    /**
     * Phase 3~5 ?�출물만 ??��?�고 ?�션??Phase 2(?�정 기획?? ?�태�??�돌�?
     * 기획??draftPlan, finalizedBrief)?� ?��??��?�? ?�용?�는 기획???�정(Phase 2) ?�는 Phase 3부???�실??가??
     */
    @Transactional
    public void rollbackToPhase2(Long sessionId) {
        GenerationSession session = generationSessionRepository.findByIdWithLecture(sessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        AuthorizationUtil.requireLectureOwner(currentUser, session.getLecture());

        session.rollbackToPhase2();
        generationSessionRepository.save(session);
    }

    /**
     * Phase 5 ?�출�?최종 문서)�???��. ?�션?� ?��??�고 Phase 4 ?�료 ?�태�??�돌?? Phase 5�??�시 ?�행?????�게 ??
     */
    @Transactional
    public void deletePhase5Output(Long sessionId) {
        GenerationSession session = generationSessionRepository.findByIdWithLecture(sessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        AuthorizationUtil.requireLectureOwner(currentUser, session.getLecture());

        session.clearFinalDocument();
        generationSessionRepository.save(session);
    }

    /**
     * Phase 3: 콘텐�??�성 (챕터 분해 �?본문 ?�성)
     * 
     * 로직 ?�명:
     * 1. ?�션 조회 �?권한 ?�인
     * 2. Phase ?�인 (PHASE2 ?�료 ?��?)
     * 3. FinalizedBrief 조회
     * 4. DecompositionAgent ?�출: 챕터�??�위 주제�?분해 (?�워??기획??기반, PDF 미사??
     * 5. WriteAgent ?�출: Markdown 본문 ?�성
     * 6. 결과 ?�?? ChapterContentList�?JSON?�로 변?�하???�션???�??
     * 7. ?�답 반환
     * 
     * @param requestDto Phase 3 ?�청 DTO (sessionId)
     * @return Phase 3 ?�답 DTO (sessionId, chapterContentList)
     */
    @Transactional
    public MaterialGenerationPhase3ResponseDto processPhase3(MaterialGenerationPhase3RequestDto requestDto) {
        log.info("Phase 3 처리: sessionId={}", requestDto.getSessionId());

        // ========== 1?�계: ?�션 조회 ==========
        GenerationSession session = generationSessionRepository.findByIdWithLecture(requestDto.getSessionId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        // ========== 2?�계: 권한 ?�인 ==========
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        
        if (!session.getUser().getId().equals(currentUser.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // ========== 3?�계: Phase ?�인 ==========
        if (session.getCurrentPhase() != GenerationPhase.PHASE2) {
            throw new BusinessException(
                    CommonErrorCode.INVALID_PHASE, 
                    "Phase 3??Phase 2 ?�료 ?�에�?진행?????�습?�다."
            );
        }

        // ========== 4?�계: FinalizedBrief 조회 ==========
        Map<String, Object> finalizedBriefMap = session.getFinalizedBriefJson();
        if (finalizedBriefMap == null) {
            throw new BusinessException(CommonErrorCode.DATA_NOT_FOUND, "FinalizedBrief가 ?�습?�다.");
        }
        FinalizedBriefDto finalizedBrief = objectMapper.convertValue(finalizedBriefMap, FinalizedBriefDto.class);

        // ========== 5?�계: DecompositionAgent ?�출 (PDF ?�이 ?�워??기획?�만 ?�용) ==========
        ChapterContentListDto chapterContentList;
        try {
            chapterContentList = decompositionAgent.decomposeChapters(finalizedBrief, null);
        } catch (Exception e) {
            log.error("Phase 3 (Decomposition) ?�패: sessionId={}, error={}", requestDto.getSessionId(), e.getMessage(), e);
            sessionRecoveryService.handleGenerationSessionFailure(
                    requestDto.getSessionId(), 
                    GenerationPhase.PHASE3, 
                    "Phase 3 (Decomposition) ?�패: " + e.getMessage()
            );
            throw new BusinessException(CommonErrorCode.AGENT_EXECUTION_FAILED, 
                    "챕터 분해???�패?�습?�다: " + e.getMessage());
        }

        // ========== 6?�계: WriteAgent ?�출 (PDF ?�이) ==========
        try {
            chapterContentList = writeAgent.writeContent(chapterContentList, null);
        } catch (Exception e) {
            log.error("Phase 3 (Write) ?�패: sessionId={}, error={}", requestDto.getSessionId(), e.getMessage(), e);
            sessionRecoveryService.handleGenerationSessionFailure(
                    requestDto.getSessionId(), 
                    GenerationPhase.PHASE3, 
                    "Phase 3 (Write) ?�패: " + e.getMessage()
            );
            throw new BusinessException(CommonErrorCode.AGENT_EXECUTION_FAILED, 
                    "본문 ?�성???�패?�습?�다: " + e.getMessage());
        }

        // ========== 8?�계: 결과 ?�??==========
        Map<String, Object> chapterContentListMap = objectMapper.convertValue(chapterContentList, Map.class);
        session.updateChapterContentList(chapterContentListMap);
        session.updatePhase(GenerationPhase.PHASE3);
        session.updateProgress(60);  // Phase 3 ?�료 = 60% 진행
        generationSessionRepository.save(session);

        return MaterialGenerationPhase3ResponseDto.builder()
                .sessionId(session.getId())
                .chapterContentList(chapterContentList)
                .progressPercentage(60)
                .message("Phase 3 ?�료: 콘텐�??�성???�료?�었?�니??")
                .build();
    }

    /**
     * Phase 4: 검�?�??�정
     * 
     * 로직 ?�명:
     * 1. ?�션 조회 �?권한 ?�인
     * 2. Phase ?�인 (PHASE3 ?�료 ?��?)
     * 3. ChapterContentList 조회
     * 4. ValidationAgent ?�출: 검??결과 충분??검�?
     * 5. ReviewAgent ?�출: ?�질 검�?
     * 6. 결과 ?�?? VerifiedContent�?JSON?�로 변?�하???�션???�??
     * 7. ?�답 반환
     * 
     * @param requestDto Phase 4 ?�청 DTO (sessionId)
     * @return Phase 4 ?�답 DTO (sessionId, verifiedContent)
     */
    @Transactional
    public MaterialGenerationPhase4ResponseDto processPhase4(MaterialGenerationPhase4RequestDto requestDto) {
        log.info("Phase 4 처리: sessionId={}", requestDto.getSessionId());

        // ========== 1?�계: ?�션 조회 ==========
        GenerationSession session = generationSessionRepository.findByIdWithLecture(requestDto.getSessionId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        // ========== 2?�계: 권한 ?�인 ==========
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        
        if (!session.getUser().getId().equals(currentUser.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // ========== 3?�계: Phase ?�인 ==========
        if (session.getCurrentPhase() != GenerationPhase.PHASE3) {
            throw new BusinessException(
                    CommonErrorCode.INVALID_PHASE, 
                    "Phase 4??Phase 3 ?�료 ?�에�?진행?????�습?�다."
            );
        }

        // ========== 4?�계: ChapterContentList 조회 ==========
        Map<String, Object> chapterContentListMap = session.getChapterContentListJson();
        if (chapterContentListMap == null) {
            throw new BusinessException(CommonErrorCode.DATA_NOT_FOUND, "ChapterContentList가 ?�습?�다.");
        }
        ChapterContentListDto chapterContentList = objectMapper.convertValue(
                chapterContentListMap, 
                ChapterContentListDto.class
        );

        // ========== 5?�계: ValidationAgent ?�출 ==========
        // 검??결과 충분??검�?
        Map<String, Object> validationResult;
        try {
            validationResult = validationAgent.validateContent(chapterContentList);
            log.info("검�?결과: {}", validationResult);
        } catch (Exception e) {
            log.error("Phase 4 (Validation) ?�패: sessionId={}, error={}", requestDto.getSessionId(), e.getMessage(), e);
            sessionRecoveryService.handleGenerationSessionFailure(
                    requestDto.getSessionId(), 
                    GenerationPhase.PHASE4, 
                    "Phase 4 (Validation) ?�패: " + e.getMessage()
            );
            throw new BusinessException(CommonErrorCode.AGENT_EXECUTION_FAILED, 
                    "콘텐�?검증에 ?�패?�습?�다: " + e.getMessage());
        }

        // ========== 6?�계: ReviewAgent ?�출 ==========
        // ?�질 검�?
        VerifiedContentDto verifiedContent;
        try {
            verifiedContent = reviewAgent.reviewContent(chapterContentList);
        } catch (Exception e) {
            log.error("Phase 4 (Review) ?�패: sessionId={}, error={}", requestDto.getSessionId(), e.getMessage(), e);
            sessionRecoveryService.handleGenerationSessionFailure(
                    requestDto.getSessionId(), 
                    GenerationPhase.PHASE4, 
                    "Phase 4 (Review) ?�패: " + e.getMessage()
            );
            throw new BusinessException(CommonErrorCode.AGENT_EXECUTION_FAILED, 
                    "콘텐�?리뷰???�패?�습?�다: " + e.getMessage());
        }

        // ========== 7?�계: 결과 ?�??==========
        Map<String, Object> verifiedContentMap = objectMapper.convertValue(verifiedContent, Map.class);
        session.updateVerifiedContent(verifiedContentMap);
        session.updatePhase(GenerationPhase.PHASE4);
        session.updateProgress(80);  // Phase 4 ?�료 = 80% 진행
        generationSessionRepository.save(session);

        return MaterialGenerationPhase4ResponseDto.builder()
                .sessionId(session.getId())
                .verifiedContent(verifiedContent)
                .progressPercentage(80)
                .message("Phase 4 ?�료: 콘텐�?검�?�??�정???�료?�었?�니??")
                .build();
    }

    /**
     * Phase 5: 최종 조립
     * 
     * 로직 ?�명:
     * 1. ?�션 조회 �?권한 ?�인
     * 2. Phase ?�인 (PHASE4 ?�료 ?��?)
     * 3. VerifiedContent 조회
     * 4. EditorAgent ?�출: 최종 문서 조립
     * 5. 결과 ?�?? 최종 Markdown 문서�??�션???�??
     * 6. ?�답 반환
     * 
     * @param requestDto Phase 5 ?�청 DTO (sessionId)
     * @return Phase 5 ?�답 DTO (sessionId, finalDocument)
     */
    @Transactional
    public MaterialGenerationPhase5ResponseDto processPhase5(MaterialGenerationPhase5RequestDto requestDto) {
        log.info("Phase 5 처리: sessionId={}", requestDto.getSessionId());

        // ========== 1?�계: ?�션 조회 ==========
        GenerationSession session = generationSessionRepository.findByIdWithLecture(requestDto.getSessionId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        // ========== 2?�계: 권한 ?�인 ==========
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        
        if (!session.getUser().getId().equals(currentUser.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // ========== 3?�계: Phase ?�인 ==========
        if (session.getCurrentPhase() != GenerationPhase.PHASE4) {
            throw new BusinessException(
                    CommonErrorCode.INVALID_PHASE, 
                    "Phase 5??Phase 4 ?�료 ?�에�?진행?????�습?�다."
            );
        }

        // ========== 4?�계: VerifiedContent 조회 ==========
        Map<String, Object> verifiedContentMap = session.getVerifiedContentJson();
        if (verifiedContentMap == null) {
            throw new BusinessException(CommonErrorCode.DATA_NOT_FOUND, "VerifiedContent가 ?�습?�다.");
        }
        VerifiedContentDto verifiedContent = objectMapper.convertValue(
                verifiedContentMap, 
                VerifiedContentDto.class
        );

        // ========== 5?�계: EditorAgent ?�출 ==========
        // 최종 문서 조립
        String finalDocument;
        try {
            finalDocument = editorAgent.assembleFinalDocument(verifiedContent);
        } catch (Exception e) {
            log.error("Phase 5 ?�패: sessionId={}, error={}", requestDto.getSessionId(), e.getMessage(), e);
            sessionRecoveryService.handleGenerationSessionFailure(
                    requestDto.getSessionId(), 
                    GenerationPhase.PHASE5, 
                    "Phase 5 ?�패: " + e.getMessage()
            );
            throw new BusinessException(CommonErrorCode.AGENT_EXECUTION_FAILED, 
                    "최종 문서 조립???�패?�습?�다: " + e.getMessage());
        }

        // ========== 6?�계: 결과 ?�??==========
        session.updateFinalDocument(finalDocument);
        generationSessionRepository.save(session);

        return MaterialGenerationPhase5ResponseDto.builder()
                .sessionId(session.getId())
                .finalDocument(finalDocument)
                .documentUrl("/api/materials/generation/" + session.getId() + "/document")
                .progressPercentage(100)
                .message("Phase 5 ?�료: 최종 문서가 ?�성?�었?�니??")
                .build();
    }

    /**
     * 최종 문서 조회
     * 
     * 로직 ?�명:
     * 1. ?�션 조회: sessionId�?GenerationSession 조회
     * 2. 권한 ?�인: ?�재 ?�용?��? ?�션 ?�유?�인지 ?�인
     * 3. 최종 문서 조회: ?�션???�?�된 finalDocument 반환
     * 
     * @param sessionId ?�션 ID
     * @return 최종 Markdown 문서
     */
    @Transactional(readOnly = true)
    public String getFinalDocument(Long sessionId) {
        // ========== 1?�계: ?�션 조회 ==========
        GenerationSession session = generationSessionRepository.findByIdWithLecture(sessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        // ========== 2?�계: 권한 ?�인 ==========
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        
        if (!session.getUser().getId().equals(currentUser.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // ========== 3?�계: 최종 문서 조회 ==========
        if (session.getFinalDocument() == null) {
            throw new BusinessException(
                    CommonErrorCode.DATA_NOT_FOUND, 
                    "최종 문서가 ?�직 ?�성?��? ?�았?�니?? Phase 5�?먼�? ?�료?�주?�요."
            );
        }

        return session.getFinalDocument();
    }

    /**
     * Phase 3-5 비동�?처리 (AsyncTaskService ?�합)
     * 
     * 로직 ?�명:
     * 1. ?�업 ?�성: AsyncTaskService???�업 ?�록
     * 2. Phase 3 처리: 콘텐�??�성 (진행�? 0% ??20% ??40% ??60%)
     * 3. Phase 4 처리: 검�?�??�정 (진행�? 60% ??80%)
     * 4. Phase 5 처리: 최종 조립 (진행�? 80% ??100%)
     * 5. ?�료 처리: ?�업 ?�태�?COMPLETED�??�데?�트
     * 
     * @param taskId ?�업 ID
     * @param sessionId ?�션 ID
     */
    @org.springframework.scheduling.annotation.Async("materialGenerationExecutor")
    public void processPhase3To5Async(String taskId, Long sessionId) {
        try {
            // ========== 1?�계: ?�션 조회 �?FinalizedBrief ?�인 ==========
            GenerationSession session = generationSessionRepository.findByIdWithLecture(sessionId)
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));
            
            Map<String, Object> finalizedBriefMap = session.getFinalizedBriefJson();
            if (finalizedBriefMap == null) {
                throw new BusinessException(CommonErrorCode.DATA_NOT_FOUND, "FinalizedBrief가 ?�습?�다.");
            }
            
            // ========== 2?�계: ko 브랜�??�합 ?�드?�인???�출 ==========
            // FastAPI?� 공유?�는 shared:finalized_brief:{sessionId} ?�에 기획?�을 ?�?�한 ???�출?�다.
            String finalizedBriefCacheKey = "shared:finalized_brief:" + sessionId;
            cacheService.set(finalizedBriefCacheKey, finalizedBriefMap, 86400); // 24?�간

            asyncTaskService.updateTaskStatus(taskId, TaskStatus.PROCESSING, 20, "Phase 3-5 ?�작: 콘텐�??�성 �?..");
            
            //  Redis Pub/Sub?�로 진행 ?�황 발행
            publishProgress(sessionId, 20, "Phase 3-5 ?�작: 콘텐�??�성 �?..", "PHASE3");
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("session_id", sessionId);
            requestBody.put("finalized_brief", finalizedBriefMap);
            
            // FastAPI ?�합 ?�드?�인???�출
            Map<String, Object> response = fastApiNoteGenClient.startPhase3To5Auto(requestBody);
            
            // ko 브랜�? status=accepted, session_id 반환 (task_id ?�음). han ?? task_id, status_url 반환.
            boolean koBranch = response != null && "accepted".equals(response.get("status")) && !response.containsKey("task_id");
            if (koBranch) {
                // ========== ko 브랜�? Redis fa:result:{sessionId} ?�링 ==========
                log.info("FastAPI Phase 3-5 (ko 브랜�? ?�업 ?�락?? sessionId={}", sessionId);
                asyncTaskService.updateTaskStatus(taskId, TaskStatus.PROCESSING, 30, "FastAPI?�서 콘텐�??�성 �?..");
                publishProgress(sessionId, 30, "FastAPI?�서 콘텐�??�성 �?..", "PHASE3");
                String finalResultKey = "fa:result:" + sessionId;
                int maxAttempts = 240;
                int attempt = 0;
                boolean completed = false;
                while (attempt < maxAttempts && !completed) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, "?�업??중단?�었?�니??");
                    }
                    attempt++;
                    String finalMarkdown = redisTemplate.opsForValue().get(finalResultKey);
                    if (finalMarkdown != null && !finalMarkdown.isBlank()) {
                        completed = true;
                        session.updateFinalDocument(finalMarkdown);
                        session.updatePhase(GenerationPhase.PHASE5);
                        session.updateProgress(100);
                        generationSessionRepository.save(session);
                        publishProgress(sessionId, 100, "?�료: 최종 문서 ?�성 ?�료", "PHASE5");
                        String resultJson = objectMapper.writeValueAsString(Map.of(
                                "sessionId", sessionId,
                                "message", "강의 ?�료 ?�성???�료?�었?�니??"
                        ));
                        asyncTaskService.updateTaskStatus(taskId, TaskStatus.COMPLETED, 100, "?�료: 최종 문서 ?�성 ?�료", resultJson);
                    }
                }
                if (!completed) {
                    throw new BusinessException(CommonErrorCode.AI_SERVER_TIMEOUT,
                            "FastAPI ?�업???�간 초과?�었?�니?? (최�? ?��??�간: 20�?");
                }
            } else {
                // ========== task_id/status_url ?�는 경우: 기존 ?�링 ==========
                if (response == null || !response.containsKey("task_id")) {
                    throw new BusinessException(CommonErrorCode.AI_SERVER_ERROR, "FastAPI ?�업 ?�록 ?�패");
                }
                String fastApiTaskId = (String) response.get("task_id");
                String statusUrl = (String) response.get("status_url");
                log.info("FastAPI Phase 3-5 ?�업 ?�록 ?�료: fastApiTaskId={}, statusUrl={}", fastApiTaskId, statusUrl);
                asyncTaskService.updateTaskStatus(taskId, TaskStatus.PROCESSING, 30, "FastAPI?�서 콘텐�??�성 �?..");
                publishProgress(sessionId, 30, "FastAPI?�서 콘텐�??�성 �?..", "PHASE3");
                int maxAttempts = 240;
                int attempt = 0;
                boolean completed = false;
                while (attempt < maxAttempts && !completed) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, "?�업??중단?�었?�니??");
                    }
                    attempt++;
                    try {
                        Map<String, Object> statusResponse = fastApiNoteGenClient.getStatusByUrl(statusUrl);
                        if (statusResponse != null) {
                            String status = (String) statusResponse.get("status");
                            Integer progress = statusResponse.get("progress") != null
                                    ? ((Number) statusResponse.get("progress")).intValue()
                                    : 0;
                            String message = (String) statusResponse.get("message");
                            int backendProgress = 30 + (progress * 60 / 100);
                            asyncTaskService.updateTaskStatus(taskId, TaskStatus.PROCESSING, backendProgress, message);
                            publishProgress(sessionId, backendProgress, message, "PHASE3-5");
                            if ("completed".equals(status)) {
                                completed = true;
                                String finalMarkdown = (String) statusResponse.get("result");
                                if (finalMarkdown != null) {
                                    session.updateFinalDocument(finalMarkdown);
                                    session.updatePhase(GenerationPhase.PHASE5);
                                    session.updateProgress(100);
                                    generationSessionRepository.save(session);
                                }
                                publishProgress(sessionId, 100, "?�료: 최종 문서 ?�성 ?�료", "PHASE5");
                                String resultJson = objectMapper.writeValueAsString(Map.of(
                                        "sessionId", sessionId,
                                        "message", "강의 ?�료 ?�성???�료?�었?�니??",
                                        "fastApiTaskId", fastApiTaskId
                                ));
                                asyncTaskService.updateTaskStatus(taskId, TaskStatus.COMPLETED, 100, "?�료: 최종 문서 ?�성 ?�료", resultJson);
                            } else if ("failed".equals(status)) {
                                String errorMessage = (String) statusResponse.get("error");
                                publishProgress(sessionId, 0, "?�류 발생: " + errorMessage, "ERROR");
                                throw new BusinessException(CommonErrorCode.AI_SERVER_ERROR,
                                        "FastAPI ?�업 ?�패: " + (errorMessage != null ? errorMessage : "?????�는 ?�류"));
                            }
                        }
                    } catch (Exception e) {
                        log.warn("FastAPI ?�태 조회 ?�패 (?�도 {}/{}): {}", attempt, maxAttempts, e.getMessage());
                    }
                }
                if (!completed) {
                    throw new BusinessException(CommonErrorCode.AI_SERVER_TIMEOUT,
                            "FastAPI ?�업???�간 초과?�었?�니?? (최�? ?��??�간: 20�?");
                }
            }
            
        } catch (BusinessException e) {
            log.error("Phase 3-5 비동�?처리 ?�패: taskId={}, sessionId={}, error={}", taskId, sessionId, e.getMessage());
            asyncTaskService.updateTaskStatus(
                taskId, 
                TaskStatus.FAILED, 
                null, 
                "?�류 발생: " + e.getMessage()
            );
        } catch (Exception e) {
            log.error("Phase 3-5 비동�?처리 ?�패: taskId={}, sessionId={}", taskId, sessionId, e);
            asyncTaskService.updateTaskStatus(
                taskId, 
                TaskStatus.FAILED, 
                null, 
                "?�류 발생: " + e.getMessage()
            );
        }
    }

    /**
     * Redis Pub/Sub?�로 진행 ?�황 발행
     * 
     * @param sessionId ?�션 ID
     * @param progress 진행�?(0-100)
     * @param message 진행 ?�황 메시지
     * @param phase ?�재 Phase
     */
    private void publishProgress(Long sessionId, int progress, String message, String phase) {
        try {
            String channel = "shared:progress:" + sessionId;
            Map<String, Object> progressData = new HashMap<>();
            progressData.put("progress", progress);
            progressData.put("message", message);
            progressData.put("phase", phase);
            progressData.put("timestamp", System.currentTimeMillis());
            
            String progressJson = objectMapper.writeValueAsString(progressData);
            redisTemplate.convertAndSend(channel, progressJson);
            
            log.debug("진행 ?�황 발행: sessionId={}, progress={}%, message={}", sessionId, progress, message);
        } catch (Exception e) {
            log.warn("진행 ?�황 발행 ?�패: sessionId={}", sessionId, e);
            // 발행 ?�패?�도 ?�업?� 계속 진행
        }
    }
}


