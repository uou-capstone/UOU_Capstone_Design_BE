package io.github.uou_capstone.aiplatform.domain.exam.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * OX 문제 DTO
 * OX_PROBLEM 유형 문제
 */
@Getter
@Setter
public class OxProblemDto {
    private String questionContent; // 문제 발문
    private String correctAnswer; // "O" 또는 "X"
    private String explanation; // 해설 내용
    private String intentType; // "Fact_Check", "Common_Misconception" 등
}
