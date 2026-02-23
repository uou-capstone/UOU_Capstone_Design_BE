package io.github.uou_capstone.aiplatform.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamStatus;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamSessionRepository;
import io.github.uou_capstone.aiplatform.domain.material.generation.GenerationPhase;
import io.github.uou_capstone.aiplatform.domain.material.generation.GenerationSession;
import io.github.uou_capstone.aiplatform.domain.material.generation.GenerationSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 세션 복구 서비스
 * 
 * 주요 기능:
 * 1. GenerationSession 실패 시 이전 Phase로 롤백
 * 2. ExamSession 실패 시 상태 복구
 * 3. 에러 메시지 저장
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionRecoveryService {

    private final GenerationSessionRepository generationSessionRepository;
    private final ExamSessionRepository examSessionRepository;

    /**
     * GenerationSession 실패 처리 및 롤백
     * 
     * @param sessionId 세션 ID
     * @param currentPhase 현재 Phase
     * @param errorMessage 에러 메시지
     */
    @Transactional
    public void handleGenerationSessionFailure(Long sessionId, GenerationPhase currentPhase, String errorMessage) {
        log.error("GenerationSession 실패 처리: sessionId={}, currentPhase={}, error={}", 
                sessionId, currentPhase, errorMessage);

        GenerationSession session = generationSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        // 에러 메시지 저장
        session.updateErrorMessage(errorMessage);

        // Phase별 롤백 전략
        GenerationPhase rollbackPhase = determineRollbackPhase(currentPhase);
        session.updatePhase(rollbackPhase);
        
        // 진행률 조정
        int rollbackProgress = getProgressForPhase(rollbackPhase);
        session.updateProgress(rollbackProgress);

        generationSessionRepository.save(session);
        
        log.info("GenerationSession 롤백 완료: sessionId={}, rollbackPhase={}, progress={}", 
                sessionId, rollbackPhase, rollbackProgress);
    }

    /**
     * Phase별 롤백 Phase 결정
     * 
     * @param currentPhase 현재 Phase
     * @return 롤백할 Phase
     */
    private GenerationPhase determineRollbackPhase(GenerationPhase currentPhase) {
        return switch (currentPhase) {
            case PHASE1 -> GenerationPhase.PHASE1; // Phase 1 실패 시 그대로 유지
            case PHASE2 -> GenerationPhase.PHASE1; // Phase 2 실패 시 Phase 1로 롤백
            case PHASE3 -> GenerationPhase.PHASE2; // Phase 3 실패 시 Phase 2로 롤백
            case PHASE4 -> GenerationPhase.PHASE3; // Phase 4 실패 시 Phase 3로 롤백
            case PHASE5 -> GenerationPhase.PHASE4; // Phase 5 실패 시 Phase 4로 롤백
            case COMPLETED -> GenerationPhase.PHASE5; // 완료 상태는 Phase 5로 롤백
            case FAILED -> GenerationPhase.PHASE1; // 실패 상태는 Phase 1로 롤백
        };
    }

    /**
     * Phase별 진행률 반환
     * 
     * @param phase Phase
     * @return 진행률 (0-100)
     */
    private int getProgressForPhase(GenerationPhase phase) {
        return switch (phase) {
            case PHASE1 -> 20;
            case PHASE2 -> 40;
            case PHASE3 -> 60;
            case PHASE4 -> 80;
            case PHASE5 -> 100;
            case COMPLETED -> 100;
            case FAILED -> 0;
        };
    }

    /**
     * ExamSession 실패 처리
     * 
     * @param examSessionId 시험 세션 ID
     * @param errorMessage 에러 메시지
     */
    @Transactional
    public void handleExamSessionFailure(Long examSessionId, String errorMessage) {
        log.error("ExamSession 실패 처리: examSessionId={}, error={}", examSessionId, errorMessage);

        ExamSession session = examSessionRepository.findById(examSessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        // 상태를 FAILED로 변경
        session.markAsFailed();
        
        examSessionRepository.save(session);
        
        log.info("ExamSession 실패 상태 저장 완료: examSessionId={}", examSessionId);
    }

    /**
     * GenerationSession 복구 (에러 메시지 제거 및 재시도 가능 상태로 변경)
     * 
     * @param sessionId 세션 ID
     */
    @Transactional
    public void recoverGenerationSession(Long sessionId) {
        log.info("GenerationSession 복구 시작: sessionId={}", sessionId);

        GenerationSession session = generationSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        // 에러 메시지 제거
        session.updateErrorMessage(null);

        generationSessionRepository.save(session);
        
        log.info("GenerationSession 복구 완료: sessionId={}", sessionId);
    }

    /**
     * ExamSession 복구 (FAILED 상태를 GENERATING으로 변경)
     * 
     * @param examSessionId 시험 세션 ID
     */
    @Transactional
    public void recoverExamSession(Long examSessionId) {
        log.info("ExamSession 복구 시작: examSessionId={}", examSessionId);

        ExamSession session = examSessionRepository.findById(examSessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        // 상태를 GENERATING으로 변경 (재시도 가능)
        session.updateStatus(ExamStatus.GENERATING);
        
        examSessionRepository.save(session);
        
        log.info("ExamSession 복구 완료: examSessionId={}", examSessionId);
    }
}
