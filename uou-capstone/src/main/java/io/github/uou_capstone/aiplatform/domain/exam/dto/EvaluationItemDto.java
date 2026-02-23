package io.github.uou_capstone.aiplatform.domain.exam.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 개별 문제 평가 결과
 */
@Getter
@Setter
public class EvaluationItemDto {
    private Long questionId;
    private String userAnswer;
    private Boolean isCorrect;
    private BigDecimal score;
    private String feedback;
    private Map<String, Object> evaluationDetails;
}
