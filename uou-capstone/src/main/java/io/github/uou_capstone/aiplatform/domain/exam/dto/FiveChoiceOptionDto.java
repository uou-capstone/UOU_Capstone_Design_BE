package io.github.uou_capstone.aiplatform.domain.exam.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 5지선다 선택지 DTO
 */
@Getter
@Setter
public class FiveChoiceOptionDto {
    private String id; // 선택지 번호 (1-5)
    private String content; // 선택지 내용
    private String intent; // 출제 의도 (정답인 이유 또는 오답 유도 논리)
    private Boolean isCorrect; // 정답 여부
}
