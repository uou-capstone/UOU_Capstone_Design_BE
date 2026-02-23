package io.github.uou_capstone.aiplatform.domain.exam.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Flash Card DTO
 * FLASH_CARD 유형 문제
 */
@Getter
@Setter
public class FlashCardDto {
    private String categoryTag; // 카테고리 태그 (예: "용어", "개념")
    private String frontContent; // 앞면 내용 (질문/용어)
    private String backContent; // 뒷면 내용 (답/정의)
    private String complexityLevel; // "Beginner", "Intermediate", "Advanced"
}
