package io.github.uou_capstone.aiplatform.domain.exam.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 시험 응시 요청 DTO
 * 사용자가 시험을 응시할 때 제출하는 답변
 */
@Getter
@Setter
public class ExamSubmissionRequestDto {
    @NotNull(message = "시험 세션 ID는 필수입니다.")
    private Long examSessionId;
    
    @NotEmpty(message = "답변 목록은 비어있을 수 없습니다.")
    @Valid
    private List<AnswerSubmissionDto> answers; // 문제별 답변
}
