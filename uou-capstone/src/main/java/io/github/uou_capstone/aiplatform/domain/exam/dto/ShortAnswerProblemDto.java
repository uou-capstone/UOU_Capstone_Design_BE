package io.github.uou_capstone.aiplatform.domain.exam.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 단답형/서술형 문제 DTO
 * SHORT_ANSWER 유형 문제
 */
@Getter
@Setter
public class ShortAnswerProblemDto {
    private String questionContent; // 문제 발문
    private List<String> relatedKeywords; // 관련 키워드 목록
    private String bestAnswer; // 모범 답안
    private String evaluationCriteria; // 평가 기준
}
