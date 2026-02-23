package io.github.uou_capstone.aiplatform.domain.exam.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * 시험 응시 응답 DTO
 * 채점 완료 후 결과 반환
 */
@Getter
@Builder
public class ExamSubmissionResponseDto {
    private Long examResultId;
    private Long examSessionId;
    private BigDecimal totalScore;
    private BigDecimal maxScore;
    private String overallFeedback;
    private GradingResponseDto gradingDetails; // 상세 채점 결과
}
