package io.github.uou_capstone.aiplatform.domain.exam.dto.student;

import lombok.Builder;
import lombok.Getter;

/**
 * 학생용 단답/서술 — bestAnswer / evaluationCriteria / relatedKeywords 는 포함하지 않는다.
 * (relatedKeywords 는 채점 힌트 역할이라 학생 노출에서 제외)
 */
@Getter
@Builder
public class StudentShortAnswerProblemDto {
    private Long id;             // ExamQuestion.id
    private String questionContent;
}
