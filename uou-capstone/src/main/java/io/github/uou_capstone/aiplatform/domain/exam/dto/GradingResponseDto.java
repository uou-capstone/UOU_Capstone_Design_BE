package io.github.uou_capstone.aiplatform.domain.exam.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 채점 결과 DTO
 * GradingResponse - 시험 응시 후 AI 채점 결과
 */
@Getter
@Setter
public class GradingResponseDto {
    private Long examSessionId;
    private BigDecimal totalScore; // 총점
    private BigDecimal maxScore; // 만점
    private List<QuestionGradingDto> questionGradings; // 개별 문제 채점 결과
    private String overallFeedback; // 전체 총평
    private Map<String, Object> evaluationMetadata; // 평가 메타데이터
}
