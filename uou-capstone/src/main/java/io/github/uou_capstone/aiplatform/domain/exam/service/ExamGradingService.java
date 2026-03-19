package io.github.uou_capstone.aiplatform.domain.exam.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.exam.dto.*;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamResult;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamResultRepository;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamSessionRepository;
import io.github.uou_capstone.aiplatform.domain.task.entity.TaskStatus;
import io.github.uou_capstone.aiplatform.service.AsyncTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

/**
 * 시험 채점 서비스 (v3)
 *
 * FastAPI POST /bridge/grade 단건 호출로 채점 + 피드백을 한 번에 위임한다.
 * DB 저장 및 응답 구성은 Spring Boot가 계속 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamGradingService {

    private final ExamSessionRepository examSessionRepository;
    private final ExamResultRepository examResultRepository;
    private final ObjectMapper objectMapper;
    private final AsyncTaskService asyncTaskService;
    private final WebClient aiServiceWebClient;

    /**
     * 시험 채점 (동기)
     *
     * FastAPI POST /bridge/grade 를 호출하여 채점 결과를 반환한다.
     * 채점 + 피드백 생성이 FastAPI 내부에서 한 번에 처리된다.
     *
     * 요청: { exam_type, exam_content, user_answers, prior_profile }
     * 응답: { total_score, max_score, overall_feedback, question_gradings, feedback_profile }
     */
    @Transactional(readOnly = true)
    public GradingResponseDto gradeExam(Long examSessionId, Map<String, Object> userAnswers) {
        log.info("시험 채점 시작: examSessionId={}", examSessionId);

        ExamSession examSession = examSessionRepository.findById(examSessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        Map<String, Object> examContent = examSession.getExamContentJson();
        if (examContent == null) {
            throw new BusinessException(CommonErrorCode.DATA_NOT_FOUND, "시험 내용을 찾을 수 없습니다.");
        }

        GradingResponseDto result = callBridgeGrade(
                examSession.getExamType().name(),
                examContent,
                userAnswers,
                examSession.getPriorProfileJson()
        );
        result.setExamSessionId(examSessionId);

        log.info("시험 채점 완료: examSessionId={}, totalScore={}/{}",
                examSessionId, result.getTotalScore(), result.getMaxScore());
        return result;
    }

    /**
     * 시험 채점 및 피드백 생성 (비동기)
     */
    @org.springframework.scheduling.annotation.Async("examGradingExecutor")
    public void gradeExamAsync(String taskId, Long examSessionId, Map<String, Object> userAnswers, Long examResultId) {
        try {
            asyncTaskService.updateTaskStatus(taskId, TaskStatus.PROCESSING, 10, "시험 채점 중...");

            GradingResponseDto gradingResult = gradeExam(examSessionId, userAnswers);
            asyncTaskService.updateTaskStatus(taskId, TaskStatus.PROCESSING, 50,
                    "채점 완료: " + gradingResult.getTotalScore() + "/" + gradingResult.getMaxScore());

            asyncTaskService.updateTaskStatus(taskId, TaskStatus.PROCESSING, 70, "피드백 프로필 처리 중...");

            ExamSession examSession = examSessionRepository.findById(examSessionId)
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

            if (examSession.getPriorProfileJson() != null && gradingResult.getEvaluationMetadata() != null) {
                Object feedbackProfileRaw = gradingResult.getEvaluationMetadata().get("feedback_profile");
                if (feedbackProfileRaw != null) {
                    ExamResult examResult = examResultRepository.findById(examResultId)
                            .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

                    Map<String, Object> feedbackMap = objectMapper.convertValue(
                            feedbackProfileRaw, new TypeReference<Map<String, Object>>() {});
                    examResult.updateUserFeedback(feedbackMap);
                    examResultRepository.save(examResult);
                }
            }

            asyncTaskService.updateTaskStatus(taskId, TaskStatus.PROCESSING, 90, "피드백 저장 완료");

            String resultJson = objectMapper.writeValueAsString(Map.of(
                    "examResultId", examResultId,
                    "totalScore", gradingResult.getTotalScore(),
                    "maxScore", gradingResult.getMaxScore(),
                    "message", "시험 채점 및 피드백 생성이 완료되었습니다."
            ));
            asyncTaskService.updateTaskStatus(taskId, TaskStatus.COMPLETED, 100,
                    "완료: 시험 채점 및 피드백 생성 완료", resultJson);

        } catch (Exception e) {
            log.error("시험 채점 비동기 처리 실패: taskId={}, examSessionId={}", taskId, examSessionId, e);
            asyncTaskService.updateTaskStatus(taskId, TaskStatus.FAILED, null, "오류 발생: " + e.getMessage());
        }
    }

    /**
     * 시험 채점 및 결과 저장
     */
    @Transactional
    public GradingResponseDto gradeAndSaveResult(ExamResult examResult, Map<String, Object> userAnswers) {
        log.info("시험 채점 및 결과 저장: examResultId={}", examResult.getId());

        GradingResponseDto gradingResult = gradeExam(examResult.getExamSession().getId(), userAnswers);

        examResult.updateScores(gradingResult.getTotalScore(), gradingResult.getMaxScore());
        examResult.updateOverallFeedback(gradingResult.getOverallFeedback());
        examResult.markAsCompleted();

        if (gradingResult.getEvaluationMetadata() != null) {
            Object feedbackProfileRaw = gradingResult.getEvaluationMetadata().get("feedback_profile");
            if (feedbackProfileRaw != null) {
                try {
                    Map<String, Object> feedbackMap = objectMapper.convertValue(
                            feedbackProfileRaw, new TypeReference<Map<String, Object>>() {});
                    examResult.updateUserFeedback(feedbackMap);
                    log.info("UserFeedbackProfile 저장 완료: examResultId={}", examResult.getId());
                } catch (Exception e) {
                    log.warn("UserFeedbackProfile 변환 실패: examResultId={}", examResult.getId(), e);
                }
            }
        }

        examResultRepository.save(examResult);

        log.info("시험 채점 및 결과 저장 완료: examResultId={}, totalScore={}/{}",
                examResult.getId(), gradingResult.getTotalScore(), gradingResult.getMaxScore());
        return gradingResult;
    }

    /**
     * FastAPI POST /bridge/grade 단건 호출.
     *
     * 요청: { exam_type, exam_content, user_answers, prior_profile }
     * 응답: GradingResponseDto 구조 + evaluationMetadata.feedback_profile 포함
     */
    private GradingResponseDto callBridgeGrade(
            String examType,
            Map<String, Object> examContent,
            Map<String, Object> userAnswers,
            Map<String, Object> priorProfile) {

        Map<String, Object> body = new HashMap<>();
        body.put("exam_type", examType);
        body.put("exam_content", examContent);
        body.put("user_answers", userAnswers);
        body.put("prior_profile", priorProfile);

        ObjectMapper snakeMapper = objectMapper.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        String raw = aiServiceWebClient.post()
                .uri("/bridge/grade")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .onErrorMap(e -> new BusinessException(CommonErrorCode.AI_SERVER_ERROR,
                        "채점 서비스 호출 실패: " + e.getMessage()))
                .block();

        if (raw == null || raw.isBlank()) {
            throw new BusinessException(CommonErrorCode.AI_SERVER_ERROR, "채점 서비스 응답이 비어 있습니다.");
        }

        try {
            return snakeMapper.readValue(raw, GradingResponseDto.class);
        } catch (Exception e) {
            log.error("채점 응답 파싱 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.AI_SERVER_ERROR, "채점 결과 파싱에 실패했습니다.");
        }
    }
}
