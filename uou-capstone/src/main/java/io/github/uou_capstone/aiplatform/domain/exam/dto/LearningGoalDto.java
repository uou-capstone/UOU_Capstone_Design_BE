package io.github.uou_capstone.aiplatform.domain.exam.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 학습 목표
 */
@Getter
@Setter
public class LearningGoalDto {
    private List<String> focusAreas; // 집중할 영역
    private String targetDepth; // "surface", "intermediate", "deep"
    private String questionModality; // "conceptual", "application", "analysis"
}
