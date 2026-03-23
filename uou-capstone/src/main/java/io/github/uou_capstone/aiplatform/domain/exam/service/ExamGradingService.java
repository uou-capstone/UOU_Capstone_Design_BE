package io.github.uou_capstone.aiplatform.domain.exam.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.exam.dto.FiveChoiceProblemDto;
import io.github.uou_capstone.aiplatform.domain.exam.dto.GradingResponseDto;
import io.github.uou_capstone.aiplatform.domain.exam.dto.OxProblemDto;
import io.github.uou_capstone.aiplatform.domain.exam.dto.QuestionGradingDto;
import io.github.uou_capstone.aiplatform.domain.exam.dto.ShortAnswerProblemDto;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamResult;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamResultRepository;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamSessionRepository;
import io.github.uou_capstone.aiplatform.integration.fastapi.FastApiBridgeClient;
import io.github.uou_capstone.aiplatform.domain.task.entity.TaskStatus;
import io.github.uou_capstone.aiplatform.service.AsyncTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 시험 채점 서비스 (v3)
 *
 * FastAPI POST /api/v3/bridge/grade/result 단건 호출로 채점 결과를 직접 반환받는다.
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
    private final FastApiBridgeClient fastApiBridgeClient;

    /**
     * 시험 채점 (동기)
     *
     * FastAPI POST /api/v3/bridge/grade/result 를 호출하여 채점 결과를 반환한다.
     *
     * 요청: { exam_type, problems, user_answers, lecture_content }
     * 응답: { grading: { results, total_score, overall_feedback }, passed }
     */
    @Transactional(readOnly = true)
    public GradingResponseDto gradeExam(Long examSessionId, List<Map<String, Object>> userAnswers) {
        log.info("시험 채점 시작: examSessionId={}", examSessionId);

        ExamSession examSession = examSessionRepository.findById(examSessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));

        Map<String, Object> examContent = examSession.getExamContentJson();
        if (examContent == null) {
            throw new BusinessException(CommonErrorCode.DATA_NOT_FOUND, "시험 내용을 찾을 수 없습니다.");
        }

        GradingResponseDto result = callBridgeGrade(
                examSession.getExamType(),
                examContent,
                userAnswers
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
    public void gradeExamAsync(String taskId, Long examSessionId, List<Map<String, Object>> userAnswers, Long examResultId) {
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
    public GradingResponseDto gradeAndSaveResult(ExamResult examResult, List<Map<String, Object>> userAnswers) {
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
     * FastAPI POST /api/v3/bridge/grade/result 단건 호출.
     *
     * 요청: { exam_type, problems, user_answers, lecture_content }
     * 응답: { grading: { results, total_score, overall_feedback }, passed }
     */
    private GradingResponseDto callBridgeGrade(
            ExamType examType,
            Map<String, Object> examContent,
            List<Map<String, Object>> userAnswers) {

        Map<String, Object> body = new HashMap<>();
        body.put("exam_type", toBridgeExamType(examType));
        body.put("problems", extractProblems(examType, examContent));
        body.put("user_answers", userAnswers);
        body.put("lecture_content", "");

        ObjectMapper snakeMapper = objectMapper.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        String raw = fastApiBridgeClient.gradeResult(body);

        if (raw == null || raw.isBlank()) {
            throw new BusinessException(CommonErrorCode.AI_SERVER_ERROR, "채점 서비스 응답이 비어 있습니다.");
        }

        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode gradingNode = root.has("grading") ? root.get("grading") : root;
            if (gradingNode == null || gradingNode.isMissingNode() || gradingNode.isNull()) {
                throw new BusinessException(CommonErrorCode.AI_SERVER_ERROR, "채점 응답에 grading 필드가 없습니다.");
            }

            GradingResponseDto dto = new GradingResponseDto();
            dto.setTotalScore(readScore(gradingNode.get("total_score")));
            dto.setMaxScore(readScore(gradingNode.get("max_score")));
            dto.setOverallFeedback(readText(gradingNode.get("overall_feedback")));
            dto.setQuestionGradings(parseQuestionGradings(snakeMapper, gradingNode.get("results")));

            Map<String, Object> metadata = new HashMap<>();
            if (root.has("passed")) {
                metadata.put("passed", root.get("passed").asBoolean());
            }
            if (root.has("feedback_profile")) {
                metadata.put("feedback_profile",
                        objectMapper.convertValue(root.get("feedback_profile"), new TypeReference<Map<String, Object>>() {}));
            }
            dto.setEvaluationMetadata(metadata.isEmpty() ? null : metadata);
            return dto;
        } catch (Exception e) {
            log.error("채점 응답 파싱 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.AI_SERVER_ERROR, "채점 결과 파싱에 실패했습니다.");
        }
    }

    private String toBridgeExamType(ExamType examType) {
        return switch (examType) {
            case FLASH_CARD -> "Flash_Card";
            case OX_PROBLEM -> "OX_Problem";
            case FIVE_CHOICE -> "Five_Choice";
            case SHORT_ANSWER -> "Short_Answer";
            case DEBATE -> "Debate";
        };
    }

    private List<Map<String, Object>> extractProblems(ExamType examType, Map<String, Object> examContent) {
        Object key = switch (examType) {
            case FLASH_CARD -> examContent.get("flashCards");
            case OX_PROBLEM -> examContent.get("oxProblems");
            case FIVE_CHOICE -> examContent.get("fiveChoiceProblems");
            case SHORT_ANSWER -> examContent.get("shortAnswerProblems");
            case DEBATE -> examContent.get("debateTopics");
        };

        if (key == null) {
            return List.of();
        }

        return objectMapper.convertValue(key, new TypeReference<List<Map<String, Object>>>() {});
    }

    private List<QuestionGradingDto> parseQuestionGradings(ObjectMapper snakeMapper, JsonNode resultsNode) {
        if (resultsNode == null || !resultsNode.isArray()) {
            return List.of(); // FastAPI가 results를 생략할 수 있으므로 빈 리스트 허용
        }

        List<QuestionGradingDto> questionGradings = new ArrayList<>();
        for (JsonNode resultNode : resultsNode) {
            QuestionGradingDto questionGrading = new QuestionGradingDto();
            if (resultNode.has("question_index")) {
                questionGrading.setQuestionId(resultNode.get("question_index").asLong());
            }
            questionGrading.setScore(readScore(resultNode.get("score")));
            questionGrading.setFeedback(readText(resultNode.get("feedback")));
            if (resultNode.has("passed")) {
                questionGrading.setIsCorrect(resultNode.get("passed").asBoolean());
            }
            if (resultNode.has("user_answer")) {
                questionGrading.setUserAnswer(readText(resultNode.get("user_answer")));
            }
            if (resultNode.has("correct_answer")) {
                questionGrading.setCorrectAnswer(readText(resultNode.get("correct_answer")));
            }

            Map<String, Object> details = snakeMapper.convertValue(
                    resultNode, new TypeReference<Map<String, Object>>() {});
            questionGrading.setEvaluationDetails(details);
            questionGradings.add(questionGrading);
        }
        return questionGradings;
    }

    private BigDecimal readScore(JsonNode node) {
        if (node == null || node.isNull()) {
            return BigDecimal.ZERO;
        }
        return node.decimalValue();
    }

    private String readText(JsonNode node) {
        return (node == null || node.isNull()) ? null : node.asText();
    }
}
