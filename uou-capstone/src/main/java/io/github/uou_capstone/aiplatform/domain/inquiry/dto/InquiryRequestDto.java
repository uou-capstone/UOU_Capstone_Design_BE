package io.github.uou_capstone.aiplatform.domain.inquiry.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 학생이 AI 질문에 답할 때 요청.
 * 강의에 PDF가 여러 개인 경우 {@code materialId}로 평가 기준 자료를 지정한다.
 */
@Getter
@Setter
public class InquiryRequestDto {
    @NotBlank(message = "AI 질문 ID는 필수입니다.")
    private String aiQuestionId;

    @NotBlank(message = "답변 내용은 필수입니다.")
    private String answerText;

    /**
     * QA 평가에 사용할 강의 자료(PDF) ID.
     * 생략 시: 질문 콘텐츠의 materialReferences에서 ID 추출을 시도하고,
     * 그래도 없으면 해당 강의의 PDF가 1개일 때만 자동 선택한다.
     * PDF가 2개 이상이면 반드시 지정해야 한다.
     */
    private Long materialId;
}
