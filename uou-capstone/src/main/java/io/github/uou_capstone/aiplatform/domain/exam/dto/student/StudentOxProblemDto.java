package io.github.uou_capstone.aiplatform.domain.exam.dto.student;

import lombok.Builder;
import lombok.Getter;

/**
 * 학생용 OX 문제 — correctAnswer / explanation / intentType 은 포함하지 않는다.
 */
@Getter
@Builder
public class StudentOxProblemDto {
    private Long id;             // ExamQuestion.id
    private String questionContent;
}
