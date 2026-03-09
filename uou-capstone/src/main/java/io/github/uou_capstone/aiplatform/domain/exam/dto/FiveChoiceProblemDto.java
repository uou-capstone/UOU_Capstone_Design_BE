package io.github.uou_capstone.aiplatform.domain.exam.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 5지선다 문제 DTO (FIVE_CHOICE)
 * 스펙: Generate_5_Choice → mcq_problems[] (id, question_content, options[5]{id,content,intent}, correct_answer, intent_diagnosis)
 */
@Getter
@Setter
public class FiveChoiceProblemDto {
    private String questionContent; // 문제 발문 (question_content)
    private List<FiveChoiceOptionDto> options; // 5개 선택지 (id 1~5, content, intent)
    private String correctAnswer; // 정답 선택지 번호 "1"~"5" (correct_answer)
    private String intentDiagnosis; // 문제 전체 출제 의도 및 학습 진단 가이드 (intent_diagnosis)
}
