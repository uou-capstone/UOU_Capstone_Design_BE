package io.github.uou_capstone.aiplatform.domain.exam.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 토론형 시험 시작 요청 DTO
 * Phase 1: 모드 설정 및 주제 선정
 */
@Getter
@Setter
public class DebateStartRequestDto {
    @NotNull(message = "시험 세션 ID는 필수입니다")
    private Long examSessionId;

    private String mode;  // "debate", "socratic", "feynman" (선택적, 기본값: "debate")
    private String topic;  // 사용자가 지정한 주제 (선택적)
}
