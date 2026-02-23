package io.github.uou_capstone.aiplatform.domain.exam.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * 개별 답변 제출
 */
@Getter
@Setter
public class AnswerSubmissionDto {
    @NotNull(message = "문제 ID는 필수입니다.")
    private Long questionId;
    
    private String answerText; // 사용자 답변 텍스트 (단답형/서술형/토론형에서 사용)
    private String selectedOptionId; // 객관식/OX 문제의 경우 선택한 선택지 ID
    private Map<String, Object> additionalData; // 추가 데이터 (토론형 등)
}
