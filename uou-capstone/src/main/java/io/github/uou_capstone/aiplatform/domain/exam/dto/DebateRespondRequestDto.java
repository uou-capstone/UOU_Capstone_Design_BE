package io.github.uou_capstone.aiplatform.domain.exam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 토론형 시험 응답 요청 DTO
 * Phase 2: 경쟁 루프 (사용자 입력 → AI 반박)
 */
@Getter
@Setter
public class DebateRespondRequestDto {
    @NotNull(message = "시험 세션 ID는 필수입니다")
    private Long examSessionId;

    @NotBlank(message = "사용자 입력은 필수입니다")
    private String userInput;  // 사용자의 답변/입력
}
