package io.github.uou_capstone.aiplatform.domain.course.dto;

import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamStatus;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class ExamSessionSummaryDto {
    private final Long examSessionId;
    private final Long materialId;
    private final ExamType examType;
    private final ExamStatus status;
    private final Integer targetCount;
    private final LocalDateTime createdAt;

    public ExamSessionSummaryDto(ExamSession session) {
        this.examSessionId = session.getId();
        this.materialId = session.getMaterial() != null ? session.getMaterial().getId() : null;
        this.examType = session.getExamType();
        this.status = session.getStatus();
        this.targetCount = session.getTargetCount();
        this.createdAt = session.getCreatedAt();
    }
}
