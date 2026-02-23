package io.github.uou_capstone.aiplatform.domain.exam.dto;

import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import lombok.Getter;
import lombok.Setter;

/**
 * FastAPI 시험 생성 요청 DTO
 * AI 서비스(FastAPI)로 전송하는 요청 형식
 */
@Getter
@Setter
public class FastApiExamRequestDto {
    private ExamType examType;
    private Integer targetCount;
    private String lectureContent;
    private TestProfileDto userProfile; // 선택적
}
