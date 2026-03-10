package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import io.github.uou_capstone.aiplatform.domain.material.generation.GenerationPhase;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 강의별 강의자료 생성 세션 목록 항목 (GET /api/materials/generation/lectures/{lectureId}/sessions 응답용)
 * 목록용 경량 DTO. 상세/재개는 latest-session 또는 status API 사용.
 */
@Getter
@Builder
public class MaterialGenerationSessionSummaryDto {
    private Long sessionId;
    private Long lectureId;
    private GenerationPhase currentPhase;
    private Integer progressPercentage;
    private String userPrompt;
    private LocalDateTime createdAt;
    private String errorMessage;
}
