package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 강의 자료 생성 Phase 5 응답 DTO
 */
@Getter
@Builder
public class MaterialGenerationPhase5ResponseDto {
    private Long sessionId;
    private String finalDocument; // 최종 Markdown 문서
    private String documentUrl; // 문서 다운로드 URL
    private Integer progressPercentage;
    private String message;
}
