package io.github.uou_capstone.aiplatform.domain.course.report.dto.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiLearningEvidenceDto {
    private final Long sourceId;
    private final String eventKind;
    private final Long sessionId;
    private final Long lectureId;
    private final Long materialId;
    private final Integer pageNumber;
    private final Integer coverageStartPage;
    private final Integer coverageEndPage;
    private final String quizId;
    private final String quizType;
    private final Double scoreRatio;
    private final Boolean passed;
    private final List<String> weakConcepts;
    private final List<Object> missedQuestions;
    private final String diagnosticPrompt;
    private final LocalDateTime occurredAt;
}
