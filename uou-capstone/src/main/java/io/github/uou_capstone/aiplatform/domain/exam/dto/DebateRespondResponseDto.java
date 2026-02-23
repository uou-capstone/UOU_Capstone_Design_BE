package io.github.uou_capstone.aiplatform.domain.exam.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * 토론형 시험 응답 응답 DTO
 */
@Getter
@Setter
public class DebateRespondResponseDto {
    private Long examSessionId;
    private String phase;  // "PHASE2"
    private String debaterResponse;  // AI 반박 내용
    private Integer score;  // 현재 점수 (0-100)
    private Map<String, Object> evaluation;  // 평가 결과
    private Boolean isCompleted;  // 토론 종료 여부
    private String message;
}
