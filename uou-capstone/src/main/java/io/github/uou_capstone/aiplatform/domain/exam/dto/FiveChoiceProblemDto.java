package io.github.uou_capstone.aiplatform.domain.exam.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 5지선다 문제 DTO
 * FIVE_CHOICE 유형 문제
 */
@Getter
@Setter
public class FiveChoiceProblemDto {
    private String questionContent; // 문제 발문
    private List<FiveChoiceOptionDto> options; // 5개 선택지
    private String correctAnswer; // 정답 선택지 번호 (1-5)
    private String intentDiagnosis; // 문제 전체의 출제 의도 및 학습 진단 가이드
}
