package io.github.uou_capstone.aiplatform.domain.exam.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 개별 문제 채점 결과
 */
@Getter
@Setter
public class QuestionGradingDto {
    private Long questionId;
    private String userAnswer; // 사용자 답변
    private Boolean isCorrect; // 정답 여부
    private BigDecimal score; // 획득 점수
    private String feedback; // 개별 피드백
    private Map<String, Object> evaluationDetails; // 상세 평가 정보
}
