package io.github.uou_capstone.aiplatform.domain.exam.service;

import io.github.uou_capstone.aiplatform.agent.AgentRequest;
import io.github.uou_capstone.aiplatform.agent.StreamingEvent;
import io.github.uou_capstone.aiplatform.agent.exam.*;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamStatus;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamSessionRepository;
import io.github.uou_capstone.aiplatform.domain.material.entity.Material;
import io.github.uou_capstone.aiplatform.domain.material.repository.MaterialRepository;
import io.github.uou_capstone.aiplatform.domain.material.generation.GenerationSessionRepository;
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
 * 시험 생성 스트리밍 서비스
 * Agent 추론 과정을 실시간으로 스트리밍하는 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamGenerationStreamService {

    // ========== 의존성 주입 ==========
    private final ProfileAgent profileAgent;
    private final FlashCardGeneratorAgent flashCardGeneratorAgent;
    private final OxProblemGeneratorAgent oxProblemGeneratorAgent;
    private final FiveChoiceGeneratorAgent fiveChoiceGeneratorAgent;
    private final ShortAnswerGeneratorAgent shortAnswerGeneratorAgent;
    private final DebateGeneratorAgent debateGeneratorAgent;
    private final ExamSessionRepository examSessionRepository;
    private final MaterialRepository materialRepository;
    private final UserRepository userRepository;
    private final GenerationSessionRepository generationSessionRepository;

    /**
     * 시험 생성 스트리밍
     * 시험 유형에 따라 해당 GeneratorAgent의 추론 과정을 실시간으로 스트리밍
     * 
     * @param examSessionId 시험 세션 ID
     * @return 스트리밍 이벤트 Flux
     */
    @Transactional(readOnly = true)
    public Flux<StreamingEvent> streamExamGeneration(Long examSessionId) {
        log.info("시험 생성 스트리밍 시작: examSessionId={}", examSessionId);

        // ========== 1단계: 세션 조회 및 권한 확인 ==========
        ExamSession session = examSessionRepository.findById(examSessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));
        validateSessionOwner(session);

        // ========== 2단계: 강의 자료 내용 조회 ==========
        String lectureContent = getLectureContent(session.getLecture().getId());

        // ========== 3단계: Profile 스트리밍 ==========
        // ProfileAgent를 먼저 스트리밍
        Map<String, Object> priorProfileMap = session.getPriorProfileJson();
        Flux<StreamingEvent> profileStream;
        
        if (priorProfileMap != null) {
            // 이미 Profile이 있으면 스킵
            profileStream = Flux.empty();
        } else {
            // Profile 생성 스트리밍
            Map<String, Object> profileContext = new HashMap<>();
            profileContext.put("lecture_content", lectureContent);
            
            // 사용자가 제공한 Profile은 ExamGenerationService에서 처리되므로
            // 여기서는 null로 설정 (이미 생성된 Profile이 있으면 priorProfileMap에 있음)
            profileContext.put("existing_profile", null);
            
            AgentRequest profileRequest = new SimpleAgentRequest("Generate or validate profile", profileContext);
            profileStream = profileAgent.executeStreaming(profileRequest);
        }

        // ========== 4단계: 시험 유형별 문제 생성 스트리밍 ==========
        Flux<StreamingEvent> examGenerationStream = getExamGenerationStream(
                session.getExamType(),
                lectureContent,
                priorProfileMap,
                session.getTargetCount()
        );

        // ========== 5단계: Profile 스트리밍과 문제 생성 스트리밍을 순차적으로 연결 ==========
        return profileStream.concatWith(examGenerationStream);
    }

    /**
     * 시험 유형별 문제 생성 스트리밍
     * 
     * @param examType 시험 유형
     * @param lectureContent 강의 자료 내용
     * @param priorProfileMap 사전 Profile (JSON)
     * @param targetCount 생성할 문제/카드 수
     * @return 스트리밍 이벤트 Flux
     */
    private Flux<StreamingEvent> getExamGenerationStream(
            ExamType examType,
            String lectureContent,
            Map<String, Object> priorProfileMap,
            Integer targetCount) {
        
        Map<String, Object> context = new HashMap<>();
        context.put("lecture_content", lectureContent);
        context.put("profile", priorProfileMap);
        context.put("target_count", targetCount != null ? targetCount : 10); // 기본값 10

        AgentRequest request = new SimpleAgentRequest("Generate exam problems", context);

        switch (examType) {
            case FLASH_CARD:
                return flashCardGeneratorAgent.executeStreaming(request);
            
            case OX_PROBLEM:
                return oxProblemGeneratorAgent.executeStreaming(request);
            
            case FIVE_CHOICE:
                return fiveChoiceGeneratorAgent.executeStreaming(request);
            
            case SHORT_ANSWER:
                return shortAnswerGeneratorAgent.executeStreaming(request);
            
            case DEBATE:
                return debateGeneratorAgent.executeStreaming(request);
            
            default:
                throw new BusinessException(
                        CommonErrorCode.INVALID_PARAMETER,
                        "지원하지 않는 시험 유형입니다: " + examType
                );
        }
    }

    /**
     * 강의 자료 내용 조회
     * 
     * @param lectureId 강의 ID
     * @return 강의 자료 내용 (Markdown 또는 텍스트)
     */
    private String getLectureContent(Long lectureId) {
        // PDF 파일이 있으면 PDF 내용 추출
        Material pdfMaterial = materialRepository
                .findFirstByLecture_IdAndMaterialTypeOrderByCreatedAtDesc(lectureId, "PDF")
                .orElse(null);
        
        if (pdfMaterial != null) {
            // ========== PDF 텍스트 추출 ==========
            String pdfPath = pdfMaterial.getFilePath();
            String pdfText = null;
            try {
                pdfText = io.github.uou_capstone.aiplatform.util.PdfTextExtractor.extractText(pdfPath);
                log.debug("PDF 텍스트 추출 완료: lectureId={}, textLength={}", lectureId, pdfText.length());
            } catch (Exception e) {
                log.warn("PDF 텍스트 추출 실패: lectureId={}, pdfPath={}", lectureId, pdfPath, e);
                // PDF 추출 실패 시 파일 경로 사용 (FastAPI에서 처리 가능)
            }
            
            // PDF 텍스트가 성공적으로 추출된 경우 텍스트 사용, 실패한 경우 파일 경로 사용
            return (pdfText != null && !pdfText.trim().isEmpty()) ? pdfText : pdfPath;
        }

        // ========== GenerationSession에서 최종 문서 조회 ==========
        // 강의 자료 생성이 완료된 경우 최종 문서 사용
        io.github.uou_capstone.aiplatform.domain.material.generation.GenerationSession generationSession = 
                generationSessionRepository.findByLectureIdOrderByCreatedAtDesc(lectureId)
                        .stream()
                        .filter(session -> session.getFinalDocument() != null)
                        .findFirst()
                        .orElse(null);
        
        if (generationSession != null && generationSession.getFinalDocument() != null) {
            log.debug("GenerationSession에서 최종 문서 조회: lectureId={}", lectureId);
            return generationSession.getFinalDocument();
        }
        
        throw new BusinessException(
                CommonErrorCode.FILE_NOT_FOUND,
                "강의 자료를 찾을 수 없습니다. PDF를 업로드하거나 강의 내용을 제공해주세요."
        );
    }

    /**
     * 세션 소유자 확인
     */
    private void validateSessionOwner(ExamSession session) {
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
