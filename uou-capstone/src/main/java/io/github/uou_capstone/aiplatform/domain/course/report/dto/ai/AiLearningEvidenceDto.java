package io.github.uou_capstone.aiplatform.domain.course.report.dto.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiLearningEvidenceDto {
    private final String evidenceId;
    private final Long courseId;
    private final Long lectureId;
    private final Long materialId;
    private final Long studentId;
    private final Long sessionId;
    private final Integer pageNumber;
    private final String eventType;
    private final String quizType;
    private final Double scoreRatio;
    private final Boolean passed;
    private final List<String> weakConcepts;
    private final List<Object> wrongItems;
    private final Map<String, Object> evidence;
    private final LocalDateTime occurredAt;
}
