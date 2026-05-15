package io.github.uou_capstone.aiplatform.domain.exam.dto.student;

import lombok.Builder;
import lombok.Getter;

/**
 * 학생용 플래시 카드 — 뒷면(backContent) 정답은 포함하지 않는다.
 */
@Getter
@Builder
public class StudentFlashCardDto {
    private Long id;            // ExamQuestion.id
    private String frontContent; // 앞면 (질문/용어)
    private String categoryTag;
    private String complexityLevel;
}
