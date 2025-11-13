package io.github.uou_capstone.aiplatform.domain.assessment.dto;

import io.github.uou_capstone.aiplatform.domain.assessment.Assessment;
import io.github.uou_capstone.aiplatform.domain.assessment.AssessmentType;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class AssessmentSimpleDto { /// 평가 목록에 표시할 간단한 정보만 담음
    private final Long assessmentId;
    private final String title;
    private final AssessmentType type;
    private final LocalDateTime dueDate;

    public AssessmentSimpleDto(Assessment assessment) {
        this.assessmentId = assessment.getId();
        this.title = assessment.getTitle();
        this.type = assessment.getType();
        this.dueDate = assessment.getDueDate();
    }
}