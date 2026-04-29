package io.github.uou_capstone.aiplatform.domain.course.report.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class NarrativeReportDto {
    private final String summary;
    private final List<String> strengths;
    private final List<String> improvements;
    private final List<String> nextSteps;
}
