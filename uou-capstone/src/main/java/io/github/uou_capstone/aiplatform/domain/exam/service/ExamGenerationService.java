package io.github.uou_capstone.aiplatform.domain.exam.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.assessment.entity.CreatedBy;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.LectureRepository;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseAccessService;
import io.github.uou_capstone.aiplatform.domain.exam.dto.*;
import io.github.uou_capstone.aiplatform.domain.exam.dto.student.*;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamQuestion;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamStatus;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamQuestionRepository;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamSessionRepository;
import io.github.uou_capstone.aiplatform.domain.material.entity.Material;
import io.github.uou_capstone.aiplatform.domain.material.repository.MaterialRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.domain.user.repository.UserRepository;
import io.github.uou_capstone.aiplatform.domain.notification.entity.NotificationType;
import io.github.uou_capstone.aiplatform.domain.notification.service.TeacherNotificationPublisher;
import io.github.uou_capstone.aiplatform.integration.fastapi.FastApiBridgeClient;
import io.github.uou_capstone.aiplatform.service.CacheService;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import io.github.uou_capstone.aiplatform.service.AsyncTaskService;
import io.github.uou_capstone.aiplatform.service.SessionRecoveryService;
import io.github.uou_capstone.aiplatform.domain.task.entity.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 시험 생성 서비스
 *
 * <p>v2와 v3 퀴즈 파이프라인은 다르다. 동기 생성은 FastAPI v2 {@code POST /api/v2/test-gen/generate} 를 쓴다.
 * 비동기 NDJSON 스트림은 v3 Bridge {@code POST /api/v3/bridge/quiz} ({@link ExamGenerationStreamService}).
 * DB 저장·응답 조립은 Spring이 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamGenerationService {

    private final ExamSessionRepository examSessionRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final LectureRepository lectureRepository;
    private final MaterialRepository materialRepository;
    private final UserRepository userRepository;
    private final CurrentUserResolver currentUserResolver;
    private final ObjectMapper objectMapper;
    private final CacheService cacheService;
    private final AsyncTaskService asyncTaskService;
    private final SessionRecoveryService sessionRecoveryService;
    private final FastApiBridgeClient fastApiBridgeClient;
    private final TeacherNotificationPublisher teacherNotificationPublisher;
    private final CourseAccessService courseAccessService;

    /**
     * 시험 생성 요청 처리
     * 
     * 로직 설명:
     * 1. 권한 확인: 현재 로그인한 사용자가 해당 강의의 선생님인지 확인
     * 2. 강의 정보 조회: lectureId로 Lecture 엔티티 조회
     * 3. 강의 자료 조회: 강의에 업로드된 최신 PDF 또는 생성된 강의 자료 조회
     * 4. 세션 생성: ExamSession 엔티티 생성 (GENERATING 상태로 시작)
     * 5. Profile 생성/검증: ProfileAgent를 통해 TestProfile 생성 또는 검증
     * 6. 시험 유형별 문제 생성: examType에 따라 해당 GeneratorAgent 호출
     * 7. 결과 저장: 생성된 문제를 JSON으로 변환하여 세션에 저장
     * 8. 응답 반환: examSessionId와 생성된 문제 리스트를 포함한 응답 반환
     * 
     * @param requestDto 시험 생성 요청 DTO (lectureId, examType, targetCount, userProfile)
     * @return 시험 생성 응답 DTO (examSessionId, examType, questions, generatedProfile)
     */
    @Transactional
    public ExamGenerationResponseDto generateExam(ExamGenerationRequestDto requestDto) {
        return generateExam(requestDto, currentUserResolver.getUser().getEmail());
    }

    /**
     * 시험 생성 (사용자 이메일 지정)
     * 비동기 스레드 등 SecurityContext가 없는 경우 호출용.
     */
    @Transactional
    public ExamGenerationResponseDto generateExam(ExamGenerationRequestDto requestDto, String userEmail) {
        log.info("시험 생성 시작: lectureId={}, examType={}, targetCount={}", 
                requestDto.getLectureId(), requestDto.getExamType(), requestDto.getTargetCount());

        // ========== 1단계: 권한 확인 ==========
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

        // ========== 2단계: 강의 정보 조회 ==========
        // lectureId로 Lecture 엔티티 조회
        Lecture lecture = lectureRepository.findById(requestDto.getLectureId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.LECTURE_NOT_FOUND));

        // ========== 권한 확인 ==========
        // 현재 사용자가 해당 강의의 소유자인지 확인 (선생님 권한 + 강의 소유권 확인)
        io.github.uou_capstone.aiplatform.util.AuthorizationUtil.requireLectureOwner(currentUser, lecture);

        // ========== 3단계: 강의 자료 조회 ==========
        // materialId가 오면 해당 자료를 우선 사용, 없으면 강의의 최신 PDF 사용
        Material pdfMaterial = null;
        if (requestDto.getMaterialId() != null) {
            pdfMaterial = materialRepository.findById(requestDto.getMaterialId())
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.FILE_NOT_FOUND, "요청한 자료를 찾을 수 없습니다."));
            if (!pdfMaterial.getLecture().getId().equals(lecture.getId())) {
                throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "materialId가 lectureId와 일치하지 않습니다.");
            }
        } else {
            pdfMaterial = materialRepository
                    .findFirstByLecture_IdAndMaterialTypeOrderByCreatedAtDesc(
                            requestDto.getLectureId(),
                            "PDF"
                    )
                    .orElse(null);
        }
        
        String lectureContent;
        if (pdfMaterial != null) {
            String pdfPath = pdfMaterial.getFilePath();
            // 로컬에 없거나 /app/ 경로(ai-service 전용)면 추출 생략 → 경로만 넘겨 ai-service가 읽도록 함
            String pdfText = io.github.uou_capstone.aiplatform.util.PdfTextExtractor.extractTextIfLocal(pdfPath);
            if (pdfText != null && !pdfText.trim().isEmpty()) {
                log.debug("PDF 텍스트 추출 완료: lectureId={}, textLength={}", requestDto.getLectureId(), pdfText.length());
            }
            lectureContent = (pdfText != null && !pdfText.trim().isEmpty()) ? pdfText : pdfPath;
        } else {
            // PDF가 없는 경우: 요청의 lectureContent → 강의 제목·설명 순으로 사용 (PDF 없이도 시험 생성 가능)
            if (requestDto.getLectureContent() != null && !requestDto.getLectureContent().isBlank()) {
                lectureContent = requestDto.getLectureContent();
            } else {
                String title = lecture.getTitle() != null ? lecture.getTitle() : "";
                String desc = lecture.getDescription() != null ? lecture.getDescription() : "";
                lectureContent = (title + "\n\n" + desc).trim();
                if (lectureContent.isEmpty()) {
                    throw new BusinessException(
                            CommonErrorCode.FILE_NOT_FOUND,
                            "강의 자료를 찾을 수 없습니다. PDF를 업로드하거나, 요청에 lectureContent를 넣거나, 강의에 제목/설명을 입력해주세요."
                    );
                }
            }
        }

        // ========== 4단계: 세션 생성 ==========
        // ExamSession 엔티티 생성
        // - targetCount: 5지선다 스펙은 target_problem_count ≤ 15 (나머지 유형은 공통 기본값/요청값 사용)
        int requestedCount = requestDto.getTargetCount() != null ? requestDto.getTargetCount() : 10;
        int targetCount = (requestDto.getExamType() == ExamType.FIVE_CHOICE && requestedCount > 15)
                ? 15
                : requestedCount;
        ExamSession session = ExamSession.builder()
                .lecture(lecture)
                .material(pdfMaterial)
                .displayName(requestDto.getDisplayName())
                .user(currentUser)
                .examType(requestDto.getExamType())
                .targetCount(targetCount)
                .build();
        session = examSessionRepository.save(session);
        log.info("ExamSession 생성 완료: examSessionId={}", session.getId());

        // ========== 5단계: Profile 결정 (케이스 A) ==========
        // 정책: ProfileAgent(대화형 프로필 생성)는 사용하지 않는다.
        // 프론트가 userProfile(TestProfileDto) 완성본을 보내면 그대로 사용하고,
        // userProfile이 없으면 서버 기본 프로필로 진행한다.
        TestProfileDto profile = (requestDto.getUserProfile() != null)
                ? requestDto.getUserProfile()
                : getDefaultProfile();

        // Profile을 JSON으로 변환하여 세션에 저장
        Map<String, Object> profileMap = objectMapper.convertValue(profile, Map.class);
        session.updatePriorProfile(profileMap);

        // ========== 6단계: 시험 문제 생성 (v2 test-gen, 동기) ==========
        // FastAPI POST /api/v2/test-gen/generate
        List<FlashCardDto> flashCards = null;
        List<OxProblemDto> oxProblems = null;
        List<FiveChoiceProblemDto> fiveChoiceProblems = null;
        List<ShortAnswerProblemDto> shortAnswerProblems = null;
        List<DebateTopicDto> debateTopics = null;

        try {
            // FastAPI v2.7: Bridge는 Debate를 비활성화한다.
            // 토론형은 /api/exams/debate/*에서 FastAPI session event로 시작한다.
            if (requestDto.getExamType() == ExamType.DEBATE) {
                debateTopics = List.of();
            } else {
                String raw = callV2TestGenGenerate(lectureContent, requestDto.getExamType(), profile, session.getTargetCount());

                switch (requestDto.getExamType()) {
                    case FLASH_CARD    -> flashCards        = parseUnifiedGenerateFlashCards(raw);
                    case OX_PROBLEM    -> oxProblems        = parseUnifiedGenerateOxProblems(raw);
                    case FIVE_CHOICE   -> fiveChoiceProblems = parseUnifiedGenerateFiveChoice(raw);
                    case SHORT_ANSWER  -> shortAnswerProblems = parseUnifiedGenerateShortAnswer(raw);
                    default -> throw new BusinessException(
                            CommonErrorCode.INVALID_PARAMETER,
                            "지원하지 않는 시험 유형입니다: " + requestDto.getExamType()
                    );
                }
            }
        } catch (Exception e) {
            log.error("시험 생성 실패: examSessionId={}, examType={}, error={}", 
                    session.getId(), requestDto.getExamType(), e.getMessage(), e);
            sessionRecoveryService.handleExamSessionFailure(
                    session.getId(), 
                    "시험 생성 실패: " + e.getMessage()
            );
            throw new BusinessException(CommonErrorCode.AGENT_EXECUTION_FAILED, 
                    "시험 생성에 실패했습니다: " + e.getMessage());
        }

        // ========== 7단계: 결과 저장 ==========
        // 생성된 문제를 JSON으로 변환하여 세션에 저장
        Map<String, Object> examContentMap = new HashMap<>();
        examContentMap.put("examType", requestDto.getExamType().name());
        examContentMap.put("targetCount", session.getTargetCount());
        
        // 시험 유형별로 해당 리스트를 저장
        if (flashCards != null) {
            examContentMap.put("flashCards", flashCards.stream()
                    .map(card -> objectMapper.convertValue(card, Map.class))
                    .toList());
        } else if (oxProblems != null) {
            examContentMap.put("oxProblems", oxProblems.stream()
                    .map(problem -> objectMapper.convertValue(problem, Map.class))
                    .toList());
        } else if (fiveChoiceProblems != null) {
            examContentMap.put("fiveChoiceProblems", fiveChoiceProblems.stream()
                    .map(problem -> objectMapper.convertValue(problem, Map.class))
                    .toList());
        } else if (shortAnswerProblems != null) {
            examContentMap.put("shortAnswerProblems", shortAnswerProblems.stream()
                    .map(problem -> objectMapper.convertValue(problem, Map.class))
                    .toList());
        } else if (debateTopics != null) {
            examContentMap.put("debateTopics", debateTopics.stream()
                    .map(topic -> objectMapper.convertValue(topic, Map.class))
                    .toList());
        }
        
        session.updateExamContent(examContentMap);
        examSessionRepository.save(session);
        
        int totalCount = flashCards != null ? flashCards.size() : 
                        (oxProblems != null ? oxProblems.size() : 
                        (fiveChoiceProblems != null ? fiveChoiceProblems.size() : 
                        (shortAnswerProblems != null ? shortAnswerProblems.size() : 
                        (debateTopics != null ? debateTopics.size() : 0))));
        log.info("시험 생성 완료: examSessionId={}, questionCount={}", session.getId(), totalCount);

        // ========== 8단계: 응답 반환 ==========
        // 사용자가 Profile을 제공하지 않은 경우, 생성된 Profile을 응답에 포함
        TestProfileDto usedProfile = profile;
        if (fiveChoiceProblems != null) {
            fillFiveChoiceOptionCorrectFlags(fiveChoiceProblems);
        }

        // ========== 7-2단계: ExamQuestion hydration ==========
        // 응시(ExamSubmissionService) 가 examQuestionRepository 에서 문제를 읽으므로,
        // 생성된 문제를 ExamQuestion 행으로 함께 저장한다. 정답·해설 등 메타데이터는
        // questionMetadata(JSON) 에 보관. DEBATE 는 현재 빈 리스트라 행이 0개 생성된다.
        hydrateExamQuestions(session, requestDto.getExamType(),
                flashCards, oxProblems, fiveChoiceProblems, shortAnswerProblems);

        return ExamGenerationResponseDto.builder()
                .examSessionId(session.getId())
                .materialId(session.getMaterial() != null ? session.getMaterial().getId() : null)
                .examType(requestDto.getExamType())
                .flashCards(flashCards)
                .oxProblems(oxProblems)
                .fiveChoiceProblems(fiveChoiceProblems)
                .shortAnswerProblems(shortAnswerProblems)
                .debateTopics(debateTopics)
                .usedProfile(usedProfile)
                .totalCount(totalCount)
                .build();
    }

    /**
     * 시험 세션 조회
     * 
     * 로직 설명:
     * 1. 세션 조회: examSessionId로 ExamSession 조회
     * 2. 권한 확인: 현재 사용자가 세션 소유자인지 확인
     * 3. 응답 구성: 세션 정보를 포함한 응답 반환
     * 
     * @param examSessionId 시험 세션 ID
     * @return 시험 생성 응답 DTO
     */
    @Transactional(readOnly = true)
    public ExamGenerationResponseDto getExamSession(Long examSessionId) {
        // ========== 1단계: 세션 조회 ==========
        ExamSession session = examSessionRepository.findById(examSessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        // ========== 2단계: 권한 확인 ==========
        User currentUser = currentUserResolver.getUser();
        
        if (!session.getUser().getId().equals(currentUser.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // ========== 3단계: 응답 구성 ==========
        Map<String, Object> examContent = session.getExamContentJson();
        TestProfileDto usedProfile = null;
        
        if (session.getPriorProfileJson() != null) {
            usedProfile = objectMapper.convertValue(
                    session.getPriorProfileJson(), 
                    TestProfileDto.class
            );
        }

        // 시험 유형별로 해당 리스트를 조회
        List<FlashCardDto> flashCards = null;
        List<OxProblemDto> oxProblems = null;
        List<FiveChoiceProblemDto> fiveChoiceProblems = null;
        List<ShortAnswerProblemDto> shortAnswerProblems = null;
        List<DebateTopicDto> debateTopics = null;
        
        if (examContent != null) {
            if (examContent.containsKey("flashCards")) {
                List<Map<String, Object>> flashCardsMap = (List<Map<String, Object>>) examContent.get("flashCards");
                flashCards = flashCardsMap.stream()
                        .map(map -> objectMapper.convertValue(map, FlashCardDto.class))
                        .toList();
            } else if (examContent.containsKey("oxProblems")) {
                List<Map<String, Object>> oxProblemsMap = (List<Map<String, Object>>) examContent.get("oxProblems");
                oxProblems = oxProblemsMap.stream()
                        .map(map -> objectMapper.convertValue(map, OxProblemDto.class))
                        .toList();
            } else if (examContent.containsKey("fiveChoiceProblems")) {
                List<Map<String, Object>> fiveChoiceProblemsMap = (List<Map<String, Object>>) examContent.get("fiveChoiceProblems");
                fiveChoiceProblems = fiveChoiceProblemsMap.stream()
                        .map(map -> objectMapper.convertValue(map, FiveChoiceProblemDto.class))
                        .toList();
            } else if (examContent.containsKey("shortAnswerProblems")) {
                List<Map<String, Object>> shortAnswerProblemsMap = (List<Map<String, Object>>) examContent.get("shortAnswerProblems");
                shortAnswerProblems = shortAnswerProblemsMap.stream()
                        .map(map -> objectMapper.convertValue(map, ShortAnswerProblemDto.class))
                        .toList();
            } else if (examContent.containsKey("debateTopics")) {
                List<Map<String, Object>> debateTopicsMap = (List<Map<String, Object>>) examContent.get("debateTopics");
                debateTopics = debateTopicsMap.stream()
                        .map(map -> objectMapper.convertValue(map, DebateTopicDto.class))
                        .toList();
            }
        }

        int totalCount = flashCards != null ? flashCards.size() : 
                        (oxProblems != null ? oxProblems.size() : 
                        (fiveChoiceProblems != null ? fiveChoiceProblems.size() : 
                        (shortAnswerProblems != null ? shortAnswerProblems.size() : 
                        (debateTopics != null ? debateTopics.size() : 0))));

        if (fiveChoiceProblems != null) {
            fillFiveChoiceOptionCorrectFlags(fiveChoiceProblems);
        }

        return ExamGenerationResponseDto.builder()
                .examSessionId(session.getId())
                .materialId(session.getMaterial() != null ? session.getMaterial().getId() : null)
                .examType(session.getExamType())
                .flashCards(flashCards)
                .oxProblems(oxProblems)
                .fiveChoiceProblems(fiveChoiceProblems)
                .shortAnswerProblems(shortAnswerProblems)
                .debateTopics(debateTopics)
                .usedProfile(usedProfile)
                .totalCount(totalCount)
                .build();
    }

    /**
     * 시험 세션 단건 삭제
     *
     * - 강의 소유 교사만 삭제 가능
     * - Redis 캐시(있다면)도 함께 삭제
     */
    @Transactional
    public void deleteExamSession(Long examSessionId) {
        ExamSession session = examSessionRepository.findById(examSessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        User currentUser = currentUserResolver.getUser();

        io.github.uou_capstone.aiplatform.util.AuthorizationUtil.requireLectureOwner(currentUser, session.getLecture());

        cacheService.deleteExamSessionCache(examSessionId);
        examSessionRepository.delete(session);
        examSessionRepository.flush();
    }

    /**
     * 시험 생성 비동기 처리 (AsyncTaskService 통합)
     * 
     * 로직 설명:
     * 1. 작업 생성: AsyncTaskService에 작업 등록
     * 2. Profile 생성/검증 (진행률: 0% → 20%)
     * 3. 시험 문제 생성 (진행률: 20% → 80%)
     * 4. 완료 처리 (진행률: 80% → 100%)
     * 
     * @param taskId 작업 ID
     * @param requestDto 시험 생성 요청 DTO
     */
    @org.springframework.scheduling.annotation.Async("taskExecutor")
    @Transactional
    public void generateExamAsync(String taskId, ExamGenerationRequestDto requestDto, String userEmail) {
        try {
            // ========== 1단계: Profile 생성/검증 ==========
            asyncTaskService.updateTaskStatus(taskId, TaskStatus.PROCESSING, 10, "Profile 생성 중...");
            
            ExamGenerationResponseDto response = generateExam(requestDto, userEmail);
            
            // ========== 2단계: 완료 처리 ==========
            String resultJson = objectMapper.writeValueAsString(Map.of(
                "examSessionId", response.getExamSessionId(),
                "examType", response.getExamType(),
                "totalCount", response.getTotalCount(),
                "message", "시험 생성이 완료되었습니다."
            ));
            asyncTaskService.updateTaskStatus(taskId, TaskStatus.COMPLETED, 100, "완료: 시험 생성 완료", resultJson);

            notifyTeacherOfExamGeneration(requestDto.getLectureId(), response.getExamSessionId(), true, null);
        } catch (Exception e) {
            log.error("시험 생성 비동기 처리 실패: taskId={}", taskId, e);
            asyncTaskService.updateTaskStatus(
                taskId,
                TaskStatus.FAILED,
                null,
                "오류 발생: " + e.getMessage()
            );
            notifyTeacherOfExamGeneration(requestDto.getLectureId(), null, false, e.getMessage());
        }
    }

    /**
     * AI 시험 생성 비동기 완료/실패를 담당 교사에게 알림. lecture 조회 실패는 무시 (부수 효과 방지).
     * actor=null 이라 publisher 의 자기 작업 분기는 발동하지 않고, 항상 일반 알림으로 발행된다
     * (요청자가 곧 담당 교사라도 비동기 결과 통지는 받아야 하므로 의도된 동작).
     */
    private void notifyTeacherOfExamGeneration(Long lectureId, Long examSessionId, boolean success, String errorMessage) {
        try {
            Lecture lecture = lectureRepository.findById(lectureId).orElse(null);
            if (lecture == null || lecture.getCourse() == null) return;
            String lectureTitle = lecture.getTitle() != null ? lecture.getTitle() : "강의";
            if (success) {
                teacherNotificationPublisher.notifyCourseTeacher(
                        lecture.getCourse(),
                        null,
                        NotificationType.AI_GENERATION_COMPLETED,
                        "AI 시험 생성 완료",
                        "'%s' 강의의 AI 시험 생성이 완료되었습니다.".formatted(lectureTitle),
                        "exam",
                        examSessionId
                );
            } else {
                teacherNotificationPublisher.notifyCourseTeacher(
                        lecture.getCourse(),
                        null,
                        NotificationType.AI_GENERATION_FAILED,
                        "AI 시험 생성 실패",
                        "'%s' 강의의 AI 시험 생성에 실패했습니다: %s".formatted(lectureTitle, errorMessage),
                        "exam",
                        null
                );
            }
        } catch (Exception ex) {
            log.warn("AI 시험 생성 알림 발행 실패: lectureId={}", lectureId, ex);
        }
    }

    /** 프로필 없을 때 사용하는 기본 프로필 (문서: user_profile 없으면 자동 생성. FastAPI get_default_test_profile과 동일 의미) */
    private TestProfileDto getDefaultProfile() {
        LearningGoalDto learningGoal = new LearningGoalDto();
        learningGoal.setFocusAreas(List.of());
        learningGoal.setTargetDepth("Concept");
        learningGoal.setQuestionModality("Balance");

        UserStatusDto userStatus = new UserStatusDto();
        userStatus.setProficiencyLevel("Intermediate");
        userStatus.setWeaknessFocus(false);

        InteractionStyleDto interactionStyle = new InteractionStyleDto();
        interactionStyle.setLanguagePreference("Korean_with_English_Terms");
        interactionStyle.setScenarioBased(false);

        FeedbackPreferenceDto feedbackPreference = new FeedbackPreferenceDto();
        feedbackPreference.setStrictness("Moderate");
        feedbackPreference.setExplanationDepth("Detailed_with_Examples");

        TestProfileDto profile = new TestProfileDto();
        profile.setLearningGoal(learningGoal);
        profile.setUserStatus(userStatus);
        profile.setInteractionStyle(interactionStyle);
        profile.setFeedbackPreference(feedbackPreference);
        profile.setScopeBoundary(ScopeBoundary.LECTURE_MATERIAL_ONLY);
        return profile;
    }

    /**
     * FastAPI v2 시험 생성 단건 호출 (v3 Bridge 와 별도 계약).
     *
     * FastAPI: POST /api/v2/test-gen/generate
     * 요청: { exam_type, target_count, lecture_content, user_profile }
     * 응답: {@code quiz} 또는 {@code problems.*} 등 — 파서가 v2/v3 유사 형태 모두 수용
     */
    private String callV2TestGenGenerate(String lectureContent, ExamType examType, TestProfileDto profile, Integer targetCount) {
        String examTypeStr = switch (examType) {
            case FLASH_CARD   -> "Flash_Card";
            case OX_PROBLEM   -> "OX_Problem";
            case FIVE_CHOICE  -> "Five_Choice";
            case SHORT_ANSWER -> "Short_Answer";
            case DEBATE       -> "Debate";
        };

        ObjectMapper snakeMapper = objectMapper.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        Map<String, Object> profileMap = profile != null
                ? snakeMapper.convertValue(profile, new TypeReference<Map<String, Object>>() {})
                : null;

        Map<String, Object> body = new HashMap<>();
        body.put("exam_type", examTypeStr);
        body.put("target_count", targetCount != null ? targetCount : 10);
        body.put("lecture_content", lectureContent);
        body.put("user_profile", profileMap);

        return fastApiBridgeClient.testGenGenerate(body);
    }

    private JsonNode readQuizResponseRoot(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(CommonErrorCode.AI_SERVER_ERROR, "퀴즈 생성 응답이 비어 있습니다.");
        }
        try {
            return objectMapper.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new BusinessException(CommonErrorCode.AI_SERVER_ERROR, "퀴즈 생성 응답 파싱에 실패했습니다.");
        }
    }

    private List<FlashCardDto> parseUnifiedGenerateFlashCards(String raw) {
        ObjectMapper snakeMapper = objectMapper.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        try {
            JsonNode root = readQuizResponseRoot(raw);
            JsonNode quiz = root.get("quiz");
            JsonNode problems = root.get("problems");
            JsonNode list = quiz != null && quiz.isArray()
                    ? quiz
                    : (problems != null && problems.has("flash_cards"))
                    ? problems.get("flash_cards")
                    : (root.has("flash_cards") ? root.get("flash_cards") : null);
            if (list == null || !list.isArray()) return List.of();
            return snakeMapper.convertValue(list, new TypeReference<List<FlashCardDto>>() {});
        } catch (Exception e) {
            log.warn("통합 generate 응답 파싱 실패(flash_cards): {}", e.getMessage());
            return List.of();
        }
    }

    /** ko 브랜치 TestGenerationResponse: problems.ox_problems 배열 */
    private List<OxProblemDto> parseUnifiedGenerateOxProblems(String raw) {
        ObjectMapper snakeMapper = objectMapper.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        try {
            JsonNode root = readQuizResponseRoot(raw);
            JsonNode quiz = root.get("quiz");
            JsonNode problems = root.get("problems");
            JsonNode list = quiz != null && quiz.isArray()
                    ? quiz
                    : (problems != null && problems.has("ox_problems"))
                    ? problems.get("ox_problems")
                    : (root.has("ox_problems") ? root.get("ox_problems") : null);
            if (list == null || !list.isArray()) return List.of();
            return snakeMapper.convertValue(list, new TypeReference<List<OxProblemDto>>() {});
        } catch (Exception e) {
            log.warn("통합 generate 응답 파싱 실패(ox_problems): {}", e.getMessage());
            return List.of();
        }
    }

    /** ko 브랜치 TestGenerationResponse: problems.mcq_problems 배열 */
    private List<FiveChoiceProblemDto> parseUnifiedGenerateFiveChoice(String raw) {
        ObjectMapper snakeMapper = objectMapper.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        try {
            JsonNode root = readQuizResponseRoot(raw);
            JsonNode quiz = root.get("quiz");
            JsonNode problems = root.get("problems");
            JsonNode list = quiz != null && quiz.isArray()
                    ? quiz
                    : (problems != null && problems.has("mcq_problems"))
                    ? problems.get("mcq_problems")
                    : (root.has("mcq_problems") ? root.get("mcq_problems") : null);
            if (list == null || !list.isArray()) return List.of();
            return snakeMapper.convertValue(list, new TypeReference<List<FiveChoiceProblemDto>>() {});
        } catch (Exception e) {
            log.warn("통합 generate 응답 파싱 실패(mcq_problems): {}", e.getMessage());
            return List.of();
        }
    }

    /** ko 브랜치 TestGenerationResponse: problems.short_answer_problems 배열 */
    private List<ShortAnswerProblemDto> parseUnifiedGenerateShortAnswer(String raw) {
        ObjectMapper snakeMapper = objectMapper.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        try {
            JsonNode root = readQuizResponseRoot(raw);
            JsonNode quiz = root.get("quiz");
            JsonNode problems = root.get("problems");
            JsonNode list = quiz != null && quiz.isArray()
                    ? quiz
                    : (problems != null && problems.has("short_answer_problems"))
                    ? problems.get("short_answer_problems")
                    : (root.has("short_answer_problems") ? root.get("short_answer_problems") : null);
            if (list == null || !list.isArray()) return List.of();
            return snakeMapper.convertValue(list, new TypeReference<List<ShortAnswerProblemDto>>() {});
        } catch (Exception e) {
            log.warn("통합 generate 응답 파싱 실패(short_answer_problems): {}", e.getMessage());
            return List.of();
        }
    }

    /** ko 브랜치 TestGenerationResponse: problems는 단일 DebateTopic 객체 */
    private List<DebateTopicDto> parseUnifiedGenerateDebate(String raw) {
        ObjectMapper snakeMapper = objectMapper.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        try {
            JsonNode root = readQuizResponseRoot(raw);
            JsonNode quiz = root.get("quiz");
            JsonNode problems = root.get("problems");
            JsonNode debateNode = quiz != null
                    ? quiz
                    : problems;
            if (debateNode == null) return List.of();
            if (debateNode.isArray() && !debateNode.isEmpty()) {
                return snakeMapper.convertValue(debateNode, new TypeReference<List<DebateTopicDto>>() {});
            }
            if (!debateNode.isObject()) return List.of();
            DebateTopicDto one = snakeMapper.convertValue(debateNode, DebateTopicDto.class);
            return List.of(one);
        } catch (Exception e) {
            log.warn("통합 generate 응답 파싱 실패(debate): {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 5지선다: 문서 예시처럼 정답은 문제 단위 correct_answer(1~5)만 옴. 옵션별 is_correct는 없으므로
     * correct_answer와 options[].id를 비교해 각 옵션의 isCorrect를 채움. (id/correct_answer가 숫자로 올 수 있어 문자열로 통일 비교)
     */
    private void fillFiveChoiceOptionCorrectFlags(List<FiveChoiceProblemDto> problems) {
        if (problems == null) return;
        for (FiveChoiceProblemDto problem : problems) {
            String correct = problem.getCorrectAnswer() != null ? String.valueOf(problem.getCorrectAnswer()) : null;
            if (correct == null || problem.getOptions() == null) continue;
            for (FiveChoiceOptionDto opt : problem.getOptions()) {
                String optId = opt.getId() != null ? String.valueOf(opt.getId()) : null;
                opt.setIsCorrect(correct.equals(optId));
            }
        }
    }

    // ============================================================
    // ExamQuestion hydration (응시 정상화)
    //
    // ExamSession.examContentJson 에만 저장되던 문제를 ExamQuestion 행으로도 함께 저장한다.
    // ExamSubmissionService 가 examQuestionRepository.findByExamSessionIdOrderByQuestionOrder()
    // 로 문제를 조회하기 때문에, hydration 이 없으면 응시 단계가 항상 "문제를 찾을 수 없습니다"
    // 로 실패한다.
    //
    // assessment 는 null 로 둔다 (v2 흐름은 ExamSession 기반, V5 마이그레이션으로 nullable).
    // FIVE_CHOICE 옵션은 ChoiceOption 테이블에 행을 만들지 않고 questionMetadata.options 에
    // [{id:"1"~"5", content, intent, isCorrect}] 형태로 저장한다 — ChoiceOption 엔티티에
    // 원본 option id 와 intent 를 담을 컬럼이 없기 때문.
    // ============================================================

    private void hydrateExamQuestions(
            ExamSession session,
            ExamType examType,
            List<FlashCardDto> flashCards,
            List<OxProblemDto> oxProblems,
            List<FiveChoiceProblemDto> fiveChoiceProblems,
            List<ShortAnswerProblemDto> shortAnswerProblems) {

        // 재실행 대비: 기존 ExamQuestion 정리 (orphanRemoval cascade 안전성을 위해 load → deleteAll)
        List<ExamQuestion> existing = examQuestionRepository.findByExamSession(session);
        if (!existing.isEmpty()) {
            examQuestionRepository.deleteAll(existing);
            examQuestionRepository.flush();
        }

        switch (examType) {
            case FLASH_CARD    -> hydrateFlashCards(session, flashCards);
            case OX_PROBLEM    -> hydrateOxProblems(session, oxProblems);
            case FIVE_CHOICE   -> hydrateFiveChoice(session, fiveChoiceProblems);
            case SHORT_ANSWER  -> hydrateShortAnswers(session, shortAnswerProblems);
            case DEBATE        -> { /* no-op: 현재 generateExam 이 debateTopics = List.of() 로 두어 hydrate 할 데이터가 없다 */ }
        }
    }

    private void hydrateFlashCards(ExamSession session, List<FlashCardDto> cards) {
        if (cards == null || cards.isEmpty()) return;
        int order = 1;
        List<ExamQuestion> rows = new java.util.ArrayList<>();
        for (FlashCardDto card : cards) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("backContent", card.getBackContent());
            metadata.put("categoryTag", card.getCategoryTag());
            metadata.put("complexityLevel", card.getComplexityLevel());
            rows.add(ExamQuestion.builder()
                    .assessment(null)
                    .examSession(session)
                    .examType(ExamType.FLASH_CARD)
                    .questionOrder(order++)
                    .questionContent(card.getFrontContent())
                    .questionMetadata(metadata)
                    .createdBy(CreatedBy.AI)
                    .build());
        }
        examQuestionRepository.saveAll(rows);
    }

    private void hydrateOxProblems(ExamSession session, List<OxProblemDto> problems) {
        if (problems == null || problems.isEmpty()) return;
        int order = 1;
        List<ExamQuestion> rows = new java.util.ArrayList<>();
        for (OxProblemDto p : problems) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("correctAnswer", p.getCorrectAnswer());
            metadata.put("explanation", p.getExplanation());
            metadata.put("intentType", p.getIntentType());
            rows.add(ExamQuestion.builder()
                    .assessment(null)
                    .examSession(session)
                    .examType(ExamType.OX_PROBLEM)
                    .questionOrder(order++)
                    .questionContent(p.getQuestionContent())
                    .questionMetadata(metadata)
                    .createdBy(CreatedBy.AI)
                    .build());
        }
        examQuestionRepository.saveAll(rows);
    }

    private void hydrateFiveChoice(ExamSession session, List<FiveChoiceProblemDto> problems) {
        if (problems == null || problems.isEmpty()) return;
        int order = 1;
        List<ExamQuestion> rows = new java.util.ArrayList<>();
        for (FiveChoiceProblemDto p : problems) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("correctAnswer", p.getCorrectAnswer());
            metadata.put("intentDiagnosis", p.getIntentDiagnosis());
            // 옵션은 원본 id("1"~"5") + content + intent + isCorrect 를 그대로 보존
            List<Map<String, Object>> optionMaps = new java.util.ArrayList<>();
            if (p.getOptions() != null) {
                for (FiveChoiceOptionDto opt : p.getOptions()) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", opt.getId());
                    m.put("content", opt.getContent());
                    m.put("intent", opt.getIntent());
                    m.put("isCorrect", opt.getIsCorrect());
                    optionMaps.add(m);
                }
            }
            metadata.put("options", optionMaps);
            rows.add(ExamQuestion.builder()
                    .assessment(null)
                    .examSession(session)
                    .examType(ExamType.FIVE_CHOICE)
                    .questionOrder(order++)
                    .questionContent(p.getQuestionContent())
                    .questionMetadata(metadata)
                    .createdBy(CreatedBy.AI)
                    .build());
        }
        examQuestionRepository.saveAll(rows);
    }

    private void hydrateShortAnswers(ExamSession session, List<ShortAnswerProblemDto> problems) {
        if (problems == null || problems.isEmpty()) return;
        int order = 1;
        List<ExamQuestion> rows = new java.util.ArrayList<>();
        for (ShortAnswerProblemDto p : problems) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("bestAnswer", p.getBestAnswer());
            metadata.put("evaluationCriteria", p.getEvaluationCriteria());
            metadata.put("relatedKeywords", p.getRelatedKeywords());
            rows.add(ExamQuestion.builder()
                    .assessment(null)
                    .examSession(session)
                    .examType(ExamType.SHORT_ANSWER)
                    .questionOrder(order++)
                    .questionContent(p.getQuestionContent())
                    .questionMetadata(metadata)
                    .createdBy(CreatedBy.AI)
                    .build());
        }
        examQuestionRepository.saveAll(rows);
    }

    // ============================================================
    // 학생용 시험 상세 조회 (정답 필드 제거된 화이트리스트 DTO 반환)
    // ============================================================

    /**
     * 학생용 시험 세션 상세 조회.
     *
     * <p>권한: 컨트롤러에서 STUDENT 로 1차 차단 + 본 메서드에서 해당 강의 Course 의 ACTIVE 수강생인지
     * {@link CourseAccessService#loadCourseAsParticipant(Long)} 으로 검증.
     *
     * <p>READY 상태가 아닌 세션(생성 중/실패) 은 학생에 노출하지 않는다.
     *
     * <p>응답은 ExamQuestion 행 + questionMetadata 에서 학생 공개 필드만 화이트리스트로 추출한다.
     * 정답·해설·평가 기준 등은 DTO 매핑 단계에서 구조적으로 제외 — 새 DTO 만 사용해 누출 방지.
     */
    @Transactional(readOnly = true)
    public StudentExamDetailDto getStudentExamSession(Long examSessionId) {
        ExamSession session = examSessionRepository.findById(examSessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        if (session.getStatus() != ExamStatus.READY) {
            throw new BusinessException(
                    CommonErrorCode.INVALID_PHASE,
                    "시험이 아직 준비되지 않았습니다. 상태: " + session.getStatus()
            );
        }

        Long courseId = session.getLecture().getCourse().getId();
        courseAccessService.loadCourseAsParticipant(courseId);

        List<ExamQuestion> questions =
                examQuestionRepository.findByExamSessionIdOrderByQuestionOrder(session.getId());

        StudentExamDetailDto.StudentExamDetailDtoBuilder builder = StudentExamDetailDto.builder()
                .examSessionId(session.getId())
                .materialId(session.getMaterial() != null ? session.getMaterial().getId() : null)
                .examType(session.getExamType())
                .displayName(session.getDisplayName())
                .totalCount(questions.size());

        switch (session.getExamType()) {
            case FLASH_CARD -> builder.flashCards(questions.stream()
                    .map(this::toStudentFlashCard).toList());
            case OX_PROBLEM -> builder.oxProblems(questions.stream()
                    .map(this::toStudentOxProblem).toList());
            case FIVE_CHOICE -> builder.fiveChoiceProblems(questions.stream()
                    .map(this::toStudentFiveChoice).toList());
            case SHORT_ANSWER -> builder.shortAnswerProblems(questions.stream()
                    .map(this::toStudentShortAnswer).toList());
            case DEBATE -> builder.debateTopics(List.of());  // 현재 generateExam 이 채우지 않음
        }

        return builder.build();
    }

    private StudentFlashCardDto toStudentFlashCard(ExamQuestion q) {
        Map<String, Object> m = q.getQuestionMetadata() != null ? q.getQuestionMetadata() : Map.of();
        return StudentFlashCardDto.builder()
                .id(q.getId())
                .frontContent(q.getQuestionContent())
                .categoryTag(asString(m.get("categoryTag")))
                .complexityLevel(asString(m.get("complexityLevel")))
                .build();
    }

    private StudentOxProblemDto toStudentOxProblem(ExamQuestion q) {
        return StudentOxProblemDto.builder()
                .id(q.getId())
                .questionContent(q.getQuestionContent())
                .build();
    }

    @SuppressWarnings("unchecked")
    private StudentFiveChoiceProblemDto toStudentFiveChoice(ExamQuestion q) {
        Map<String, Object> m = q.getQuestionMetadata() != null ? q.getQuestionMetadata() : Map.of();
        List<Map<String, Object>> rawOpts = (List<Map<String, Object>>) m.getOrDefault("options", List.of());
        List<StudentFiveChoiceProblemDto.Option> options = rawOpts.stream()
                .map(o -> StudentFiveChoiceProblemDto.Option.builder()
                        .id(asString(o.get("id")))
                        .content(asString(o.get("content")))
                        .build())
                .toList();
        return StudentFiveChoiceProblemDto.builder()
                .id(q.getId())
                .questionContent(q.getQuestionContent())
                .options(options)
                .build();
    }

    private StudentShortAnswerProblemDto toStudentShortAnswer(ExamQuestion q) {
        return StudentShortAnswerProblemDto.builder()
                .id(q.getId())
                .questionContent(q.getQuestionContent())
                .build();
    }

    private String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
