package io.github.uou_capstone.aiplatform.domain.exam.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.agent.exam.ExamGraderAgent;
import io.github.uou_capstone.aiplatform.agent.exam.FeedbackGeneratorAgent;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.exam.dto.*;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamResult;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamResultRepository;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamSessionRepository;
import io.github.uou_capstone.aiplatform.service.AsyncTaskService;
import io.github.uou_capstone.aiplatform.domain.task.entity.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * 시험 채점 서비스
 * 
 * 주요 기능:
 * 1. 시험 채점: ExamGraderAgent를 통해 답변 채점
 * 2. 비동기 채점: 긴 채점 작업을 비동기로 처리
 * 3. 피드백 생성: 사용자 피드백 프로필 생성
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamGradingService {

    private final ExamGraderAgent examGraderAgent;
    private final FeedbackGeneratorAgent feedbackGeneratorAgent;
    private final ExamSessionRepository examSessionRepository;
    private final ExamResultRepository examResultRepository;
    private final ObjectMapper objectMapper;
    private final AsyncTaskService asyncTaskService;

    /**
     * 시험 채점 (동기)
     * 
     * 로직 설명:
     * 1. 시험 세션 조회
     * 2. ExamGraderAgent 호출하여 채점
     * 3. 채점 결과 반환
     * 
     * @param examSessionId 시험 세션 ID
     * @param userAnswers 사용자 답변 (Map<questionId, answerData>)
     * @return 채점 결과
     */
    @Transactional(readOnly = true)
    public GradingResponseDto gradeExam(Long examSessionId, Map<String, Object> userAnswers) {
        log.info("시험 채점 시작: examSessionId={}", examSessionId);

        // ========== 1단계: 시험 세션 조회 ==========
        ExamSession examSession = examSessionRepository.findById(examSessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        // ========== 2단계: 시험 내용 조회 ==========
        Map<String, Object> examContent = examSession.getExamContentJson();
        if (examContent == null) {
            throw new BusinessException(CommonErrorCode.DATA_NOT_FOUND, "시험 내용을 찾을 수 없습니다.");
        }

        // ========== 3단계: Agent 호출 ==========
        // ExamGraderAgent를 통해 답변 채점
        GradingResponseDto gradingResult = examGraderAgent.gradeExam(
                examSession.getExamType(),
                examContent,
                userAnswers
        );

        // examSessionId 설정
        gradingResult.setExamSessionId(examSessionId);

        log.info("시험 채점 완료: examSessionId={}, totalScore={}/{}", 
                examSessionId, gradingResult.getTotalScore(), gradingResult.getMaxScore());

        return gradingResult;
    }

    /**
     * 시험 채점 및 피드백 생성 (비동기)
     * 
     * 로직 설명:
     * 1. 작업 생성: AsyncTaskService에 작업 등록
     * 2. 채점 수행 (진행률: 0% → 50%)
     * 3. 피드백 생성 (진행률: 50% → 100%)
     * 4. 완료 처리
     * 
     * @param taskId 작업 ID
     * @param examSessionId 시험 세션 ID
     * @param userAnswers 사용자 답변
     * @param examResultId 시험 결과 ID (저장용)
     */
    @org.springframework.scheduling.annotation.Async("examGradingExecutor")
    public void gradeExamAsync(String taskId, Long examSessionId, Map<String, Object> userAnswers, Long examResultId) {
        try {
            // ========== 1단계: 채점 시작 ==========
            asyncTaskService.updateTaskStatus(taskId, TaskStatus.PROCESSING, 10, "시험 채점 중...");

            // ========== 2단계: 채점 수행 ==========
            GradingResponseDto gradingResult = gradeExam(examSessionId, userAnswers);
            asyncTaskService.updateTaskStatus(taskId, TaskStatus.PROCESSING, 50, "채점 완료: " + 
                    gradingResult.getTotalScore() + "/" + gradingResult.getMaxScore());

            // ========== 3단계: 피드백 생성 ==========
            asyncTaskService.updateTaskStatus(taskId, TaskStatus.PROCESSING, 70, "피드백 프로필 생성 중...");

            ExamSession examSession = examSessionRepository.findById(examSessionId)
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

            Map<String, Object> priorProfileMap = examSession.getPriorProfileJson();
            if (priorProfileMap != null) {
                TestProfileDto priorProfile = objectMapper.convertValue(priorProfileMap, TestProfileDto.class);
                Map<String, Object> examContent = examSession.getExamContentJson();

                UserFeedbackProfileDto userFeedbackProfile = feedbackGeneratorAgent.generateUserFeedback(
                        priorProfile,
                        gradingResult,
                        examContent
                );

                // ========== 4단계: 결과 저장 ==========
                ExamResult examResult = examResultRepository.findById(examResultId)
                        .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

                Map<String, Object> userFeedbackProfileMap = objectMapper.convertValue(userFeedbackProfile, Map.class);
                examResult.updateUserFeedback(userFeedbackProfileMap);
                examResultRepository.save(examResult);

                asyncTaskService.updateTaskStatus(taskId, TaskStatus.PROCESSING, 90, "피드백 프로필 생성 완료");
            }

            // ========== 5단계: 완료 처리 ==========
            String resultJson = objectMapper.writeValueAsString(Map.of(
                    "examResultId", examResultId,
                    "totalScore", gradingResult.getTotalScore(),
                    "maxScore", gradingResult.getMaxScore(),
                    "message", "시험 채점 및 피드백 생성이 완료되었습니다."
            ));
            asyncTaskService.updateTaskStatus(taskId, TaskStatus.COMPLETED, 100, "완료: 시험 채점 및 피드백 생성 완료", resultJson);

        } catch (Exception e) {
            log.error("시험 채점 비동기 처리 실패: taskId={}, examSessionId={}", taskId, examSessionId, e);
            asyncTaskService.updateTaskStatus(
                    taskId,
                    TaskStatus.FAILED,
                    null,
                    "오류 발생: " + e.getMessage()
            );
        }
    }

    /**
     * 시험 채점 및 결과 저장
     * 
     * 로직 설명:
     * 1. 채점 수행
     * 2. ExamResult 업데이트
     * 3. 피드백 생성 (선택적)
     * 
     * @param examResult 시험 결과 엔티티
     * @param userAnswers 사용자 답변
     * @return 채점 결과
     */
    @Transactional
    public GradingResponseDto gradeAndSaveResult(ExamResult examResult, Map<String, Object> userAnswers) {
        log.info("시험 채점 및 결과 저장: examResultId={}", examResult.getId());

        // ========== 1단계: 채점 수행 ==========
        GradingResponseDto gradingResult = gradeExam(
                examResult.getExamSession().getId(),
                userAnswers
        );

        // ========== 2단계: 결과 업데이트 ==========
        examResult.updateScores(gradingResult.getTotalScore(), gradingResult.getMaxScore());
        examResult.updateOverallFeedback(gradingResult.getOverallFeedback());
        examResult.markAsCompleted();

        // ========== 3단계: 피드백 생성 (선택적) ==========
        ExamSession examSession = examResult.getExamSession();
        Map<String, Object> priorProfileMap = examSession.getPriorProfileJson();
        
        if (priorProfileMap != null) {
            try {
                TestProfileDto priorProfile = objectMapper.convertValue(priorProfileMap, TestProfileDto.class);
                Map<String, Object> examContent = examSession.getExamContentJson();

                UserFeedbackProfileDto userFeedbackProfile = feedbackGeneratorAgent.generateUserFeedback(
                        priorProfile,
                        gradingResult,
                        examContent
                );

                Map<String, Object> userFeedbackProfileMap = objectMapper.convertValue(userFeedbackProfile, Map.class);
                examResult.updateUserFeedback(userFeedbackProfileMap);
                log.info("UserFeedbackProfile 생성 완료: examResultId={}", examResult.getId());
            } catch (Exception e) {
                log.warn("UserFeedbackProfile 생성 실패: examResultId={}", examResult.getId(), e);
                // 피드백 생성 실패해도 채점 결과는 저장
            }
        }

        examResultRepository.save(examResult);

        log.info("시험 채점 및 결과 저장 완료: examResultId={}, totalScore={}/{}",
                examResult.getId(), gradingResult.getTotalScore(), gradingResult.getMaxScore());

        return gradingResult;
    }
}
