package io.github.uou_capstone.aiplatform.domain.exam.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * 토론형 시험 시작 응답 DTO
 */
@Getter
@Setter
public class DebateStartResponseDto {
    private Long examSessionId;
    private String phase;  // "PHASE1"
    private String topic;  // 선정된 토론 주제
    private Map<String, Object> settings;  // Phase 1 설정 결과
    private String message;
}
