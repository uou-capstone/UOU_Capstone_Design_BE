package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * 강의 자료 생성 Phase 2 요청 DTO
 * 사용자 피드백 또는 승인 요청
 */
@Getter
@Setter
public class MaterialGenerationPhase2RequestDto {
    @NotNull(message = "세션 ID는 필수입니다.")
    private Long sessionId;
    
    // action은 프론트가 누락/대문자 전송하는 경우가 있어 서버에서 기본값을 보정한다.
    // (null 허용) 값이 들어왔다면 feedback/confirm만 허용 + 대소문자 무시
    @Pattern(regexp = "(?i)^(feedback|confirm)$", message = "액션은 'feedback' 또는 'confirm'이어야 합니다.")
    private String action; // "feedback" 또는 "confirm"
    
    private String feedback; // 피드백 내용 (action이 "feedback"일 때 필수)
}
