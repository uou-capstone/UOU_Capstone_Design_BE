package io.github.uou_capstone.aiplatform.domain.exam.dto;

import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 시험 생성 요청 DTO
 */
@Getter
@Setter
public class ExamGenerationRequestDto {
    @NotNull(message = "강의 ID는 필수입니다.")
    private Long lectureId;
    
    @NotNull(message = "시험 유형은 필수입니다.")
    private ExamType examType; // FLASH_CARD, OX_PROBLEM, FIVE_CHOICE, SHORT_ANSWER, DEBATE
    
    @Min(value = 1, message = "생성할 문제/카드 수는 1개 이상이어야 합니다.")
    private Integer targetCount; // 생성할 문제/카드 수 (기본값: 10)
    
    private String lectureContent; // 강의 자료 텍스트 (Markdown 등) - 선택적
    private TestProfileDto userProfile; // 사용자 프로필 (선택적, 없으면 자동 생성)
}
