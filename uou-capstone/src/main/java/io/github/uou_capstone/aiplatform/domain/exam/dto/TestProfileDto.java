package io.github.uou_capstone.aiplatform.domain.exam.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * TestProfile DTO
 * 시험 생성 전 사용자 설정 수집 (사전 Profile)
 * FastAPI의 TestProfile 스키마와 동일
 */
@Getter
@Setter
public class TestProfileDto {
    private LearningGoalDto learningGoal;
    private UserStatusDto userStatus;
    private InteractionStyleDto interactionStyle;
    private FeedbackPreferenceDto feedbackPreference;
    private ScopeBoundary scopeBoundary; // LECTURE_MATERIAL_ONLY, ALLOW_EXTERNAL_KNOWLEDGE
}
