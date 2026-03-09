package io.github.uou_capstone.aiplatform.domain.exam.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.github.uou_capstone.aiplatform.agent.exam.DebateGeneratorAgent;
import io.github.uou_capstone.aiplatform.agent.exam.FlashCardGeneratorAgent;
import io.github.uou_capstone.aiplatform.agent.exam.FiveChoiceGeneratorAgent;
import io.github.uou_capstone.aiplatform.agent.exam.OxProblemGeneratorAgent;
import io.github.uou_capstone.aiplatform.agent.exam.ProfileAgent;
import io.github.uou_capstone.aiplatform.agent.exam.ShortAnswerGeneratorAgent;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.LectureRepository;
import io.github.uou_capstone.aiplatform.domain.exam.dto.*;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamStatus;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamSessionRepository;
import io.github.uou_capstone.aiplatform.domain.material.entity.Material;
import io.github.uou_capstone.aiplatform.domain.material.repository.MaterialRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.domain.user.repository.UserRepository;
import io.github.uou_capstone.aiplatform.service.CacheService;
import io.github.uou_capstone.aiplatform.service.AsyncTaskService;
import io.github.uou_capstone.aiplatform.service.SessionRecoveryService;
import io.github.uou_capstone.aiplatform.domain.task.entity.TaskStatus;
import io.github.uou_capstone.aiplatform.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 시험 생성 서비스
 * Version 2의 5가지 시험 유형 생성을 관리하는 서비스
 * 
 * 시험 유형:
 * 1. FLASH_CARD: 플래시카드
 * 2. OX_PROBLEM: OX 문제
 * 3. FIVE_CHOICE: 5지선다 문제
 * 4. SHORT_ANSWER: 단답형/서술형 문제
 * 5. DEBATE: 토론형 문제
 * 
 * 생성 프로세스:
 * 1. Profile 생성/검증 (ProfileAgent)
 * 2. 시험 유형별 문제 생성 (각 GeneratorAgent)
 * 3. 결과 저장 및 세션 업데이트
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamGenerationService {

    // ========== 의존성 주입 ==========
    private final ProfileAgent profileAgent;  // Profile 생성 Agent
    private final FlashCardGeneratorAgent flashCardGeneratorAgent;  // 플래시카드 생성 Agent
    private final OxProblemGeneratorAgent oxProblemGeneratorAgent;  // OX 문제 생성 Agent
    private final FiveChoiceGeneratorAgent fiveChoiceGeneratorAgent;  // 5지선다 생성 Agent
    private final ShortAnswerGeneratorAgent shortAnswerGeneratorAgent;  // 단답형/서술형 생성 Agent
    private final DebateGeneratorAgent debateGeneratorAgent;  // 토론형 생성 Agent
    private final ExamSessionRepository examSessionRepository;
    private final LectureRepository lectureRepository;
    private final MaterialRepository materialRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;  // JSON 변환용
    private final CacheService cacheService;  // Redis 캐싱 서비스
    private final AsyncTaskService asyncTaskService;  // 비동기 작업 상태 추적
    private final SessionRecoveryService sessionRecoveryService; // 세션 복구 서비스

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
        log.info("시험 생성 시작: lectureId={}, examType={}, targetCount={}", 
                requestDto.getLectureId(), requestDto.getExamType(), requestDto.getTargetCount());

        // ========== 1단계: 권한 확인 ==========
        // 현재 로그인한 사용자 정보 조회
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
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
        // 강의에 업로드된 최신 PDF 또는 생성된 강의 자료 조회
        // 먼저 PDF를 찾고, 없으면 생성된 강의 자료를 찾음
        Material pdfMaterial = materialRepository
                .findFirstByLecture_IdAndMaterialTypeOrderByCreatedAtDesc(
                        requestDto.getLectureId(), 
                        "PDF"
                )
                .orElse(null);
        
        String lectureContent;
        if (pdfMaterial != null) {
            // PDF 파일이 있는 경우, PDF 텍스트 추출 시도
            String pdfPath = pdfMaterial.getFilePath();
            String pdfText = null;
            try {
                // ========== PDF 텍스트 추출 ==========
                // PdfTextExtractor를 사용하여 PDF 내용 추출
                pdfText = io.github.uou_capstone.aiplatform.util.PdfTextExtractor.extractText(pdfPath);
                log.debug("PDF 텍스트 추출 완료: lectureId={}, textLength={}", requestDto.getLectureId(), pdfText.length());
            } catch (Exception e) {
                log.warn("PDF 텍스트 추출 실패: lectureId={}, pdfPath={}", requestDto.getLectureId(), pdfPath, e);
                // PDF 추출 실패 시 파일 경로 사용 (FastAPI에서 처리 가능)
            }
            
            // PDF 텍스트가 성공적으로 추출된 경우 텍스트 사용, 실패한 경우 파일 경로 사용
            lectureContent = (pdfText != null && !pdfText.trim().isEmpty()) ? pdfText : pdfPath;
        } else {
            // PDF가 없는 경우, requestDto에서 제공된 lectureContent 사용
            if (requestDto.getLectureContent() == null || requestDto.getLectureContent().isEmpty()) {
                throw new BusinessException(
                        CommonErrorCode.FILE_NOT_FOUND, 
                        "강의 자료를 찾을 수 없습니다. PDF를 업로드하거나 강의 내용을 제공해주세요."
                );
            }
            lectureContent = requestDto.getLectureContent();
        }

        // ========== 4단계: 세션 생성 ==========
        // ExamSession 엔티티 생성
        // - lecture: 강의 정보
        // - user: 현재 로그인한 사용자 (선생님)
        // - examType: 시험 유형 (FLASH_CARD, OX_PROBLEM, 등)
        // - targetCount: 생성할 문제/카드 수
        // - status: GENERATING으로 초기화
        ExamSession session = ExamSession.builder()
                .lecture(lecture)
                .user(currentUser)
                .examType(requestDto.getExamType())
                .targetCount(requestDto.getTargetCount() != null ? requestDto.getTargetCount() : 10)
                .build();
        session = examSessionRepository.save(session);
        log.info("ExamSession 생성 완료: examSessionId={}", session.getId());

        // ========== 5단계: Profile 생성/검증 (캐싱 적용) ==========
        // 설계: 프로필이 없으면 반드시 생성함 (문서: user_profile 선택, 없으면 자동 생성)
        // 순서: 1) 사용자 제공 프로필 사용 2) 캐시 Hit 시 캐시 사용 3) ProfileAgent로 생성 4) API 404 시 기본 프로필
        // 5-1. 캐시 키 생성: 강의 내용의 MD5 해시
        String contentHash = HashUtil.generateMD5Hash(lectureContent);
        
        // 5-2. Redis에서 Profile 조회 (프로필 없을 때만)
        TestProfileDto profile;
        Optional<String> cachedProfileJson = cacheService.getProfile(contentHash);
        
        if (cachedProfileJson.isPresent() && requestDto.getUserProfile() == null) {
            // Cache Hit: 캐시된 Profile 사용 (Spring=camelCase, FastAPI(ko)=snake_case 공유 캐시 대응)
            log.info("Profile cache hit: contentHash={}", contentHash);
            try {
                profile = objectMapper.readValue(cachedProfileJson.get(), TestProfileDto.class);
                // FastAPI(ko)가 쓴 snake_case 캐시면 learningGoal 등이 null일 수 있음 → snake_case로 재시도
                if (profile.getLearningGoal() == null) {
                    ObjectMapper snakeMapper = objectMapper.copy()
                            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
                    profile = snakeMapper.readValue(cachedProfileJson.get(), TestProfileDto.class);
                }
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse cached profile, generating new one", e);
                profile = callProfileAgentOrDefault(lectureContent, requestDto.getUserProfile());
            }
        } else {
            // 프로필 없음 → 생성: ProfileAgent(/api/test-gen/profile) 호출, 404 시 기본 프로필 사용
            log.info("Profile cache miss or user profile provided: contentHash={}", contentHash);
            profile = callProfileAgentOrDefault(lectureContent, requestDto.getUserProfile());

            // 5-3. 생성된 Profile을 Redis에 캐싱 (사용자가 Profile을 제공하지 않은 경우만)
            if (requestDto.getUserProfile() == null) {
                try {
                    String profileJson = objectMapper.writeValueAsString(profile);
                    cacheService.cacheProfile(contentHash, profileJson);
                } catch (JsonProcessingException e) {
                    log.warn("Failed to cache profile", e);
                }
            }
        }

        // Profile을 JSON으로 변환하여 세션에 저장
        Map<String, Object> profileMap = objectMapper.convertValue(profile, Map.class);
        session.updatePriorProfile(profileMap);

        // ========== 6단계: 시험 유형별 문제 생성 ==========
        // examType에 따라 해당 GeneratorAgent 호출
        // 각 시험 유형별로 별도의 리스트를 생성
        List<FlashCardDto> flashCards = null;
        List<OxProblemDto> oxProblems = null;
        List<FiveChoiceProblemDto> fiveChoiceProblems = null;
        List<ShortAnswerProblemDto> shortAnswerProblems = null;
        List<DebateTopicDto> debateTopics = null;
        
        try {
            switch (requestDto.getExamType()) {
            case FLASH_CARD:
                // 플래시카드 생성
                // FlashCardGeneratorAgent.generateFlashCards() 호출
                // - lectureContent: 강의 자료 내용
                // - profile: 생성/검증된 Profile
                // - targetCount: 생성할 카드 수
                // - 반환값: List<FlashCardDto>
                flashCards = flashCardGeneratorAgent.generateFlashCards(
                        lectureContent, 
                        profile, 
                        session.getTargetCount()
                );
                break;

            case OX_PROBLEM:
                // OX 문제 생성
                // OxProblemGeneratorAgent.generateOxProblems() 호출
                // - lectureContent: 강의 자료 내용
                // - profile: 생성/검증된 Profile
                // - targetCount: 생성할 문제 수
                // - 반환값: List<OxProblemDto>
                oxProblems = oxProblemGeneratorAgent.generateOxProblems(
                        lectureContent, 
                        profile, 
                        session.getTargetCount()
                );
                break;

            case FIVE_CHOICE:
                // 5지선다 문제 생성
                // FiveChoiceGeneratorAgent.generateFiveChoiceProblems() 호출
                // - lectureContent: 강의 자료 내용
                // - profile: 생성/검증된 Profile
                // - targetCount: 생성할 문제 수
                // - 반환값: List<FiveChoiceProblemDto>
                fiveChoiceProblems = fiveChoiceGeneratorAgent.generateFiveChoiceProblems(
                        lectureContent, 
                        profile, 
                        session.getTargetCount()
                );
                break;

            case SHORT_ANSWER:
                // 단답형/서술형 문제 생성
                // ShortAnswerGeneratorAgent.generateShortAnswerProblems() 호출
                // - lectureContent: 강의 자료 내용
                // - profile: 생성/검증된 Profile
                // - targetCount: 생성할 문제 수
                // - 반환값: List<ShortAnswerProblemDto>
                shortAnswerProblems = shortAnswerGeneratorAgent.generateShortAnswerProblems(
                        lectureContent, 
                        profile, 
                        session.getTargetCount()
                );
                break;

            case DEBATE:
                // 토론형 문제 생성
                // DebateGeneratorAgent.generateDebateTopics() 호출
                // - lectureContent: 강의 자료 내용
                // - profile: 생성/검증된 Profile
                // - targetCount: 생성할 문제 수
                // - 반환값: List<DebateTopicDto>
                debateTopics = debateGeneratorAgent.generateDebateTopics(
                        lectureContent, 
                        profile, 
                        session.getTargetCount()
                );
                break;

                default:
                    throw new BusinessException(
                            CommonErrorCode.INVALID_PARAMETER, 
                            "지원하지 않는 시험 유형입니다: " + requestDto.getExamType()
                    );
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

        return ExamGenerationResponseDto.builder()
                .examSessionId(session.getId())
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
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        
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

        return ExamGenerationResponseDto.builder()
                .examSessionId(session.getId())
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
    public void generateExamAsync(String taskId, ExamGenerationRequestDto requestDto) {
        try {
            // ========== 1단계: Profile 생성/검증 ==========
            asyncTaskService.updateTaskStatus(taskId, TaskStatus.PROCESSING, 10, "Profile 생성 중...");
            
            ExamGenerationResponseDto response = generateExam(requestDto);
            
            // ========== 2단계: 완료 처리 ==========
            String resultJson = objectMapper.writeValueAsString(Map.of(
                "examSessionId", response.getExamSessionId(),
                "examType", response.getExamType(),
                "totalCount", response.getTotalCount(),
                "message", "시험 생성이 완료되었습니다."
            ));
            asyncTaskService.updateTaskStatus(taskId, TaskStatus.COMPLETED, 100, "완료: 시험 생성 완료", resultJson);
            
        } catch (Exception e) {
            log.error("시험 생성 비동기 처리 실패: taskId={}", taskId, e);
            asyncTaskService.updateTaskStatus(
                taskId, 
                TaskStatus.FAILED, 
                null, 
                "오류 발생: " + e.getMessage()
            );
        }
    }

    /**
     * 프로필 없을 때 생성: ProfileAgent(/api/test-gen/profile) 호출.
     * 404(엔드포인트 미구현) 시 기본 프로필로 폴백하여 "프로필 없으면 생성"을 보장하고 502 방지.
     */
    private TestProfileDto callProfileAgentOrDefault(String lectureContent, TestProfileDto existingProfile) {
        try {
            return profileAgent.generateOrValidateProfile(lectureContent, existingProfile);
        } catch (WebClientResponseException e) {
            if (HttpStatusCode.valueOf(404).equals(e.getStatusCode())) {
                log.warn("[ProfileAgent] POST /api/test-gen/profile 404 - ai-service에 해당 엔드포인트가 없습니다. 기본 프로필로 생성하여 진행합니다.");
                return getDefaultProfile();
            }
            throw e;
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
     * 프로필 대화 1턴 (문서: ai-service-endpoint-request.md §2, §4)
     * 사용자가 에이전트와 대화로 프로필을 채울 때, 턴마다 이 메서드를 호출.
     * status가 COMPLETE가 될 때까지 반복한 뒤, updatedProfile을 시험 생성 요청의 userProfile로 전달.
     */
    @Transactional(readOnly = true)
    public ProfileConversationResponseDto chatProfileTurn(ProfileConversationRequestDto request) {
        return profileAgent.chatTurn(
                request.getLectureContent(),
                request.getExamType(),
                request.getExistingProfile(),
                request.getUserMessage()
        );
    }
}
