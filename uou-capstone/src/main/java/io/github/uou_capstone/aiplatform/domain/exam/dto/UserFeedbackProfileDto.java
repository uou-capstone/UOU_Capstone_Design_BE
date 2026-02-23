package io.github.uou_capstone.aiplatform.domain.exam.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * User 피드백 Profile DTO
 * 시험 응시 후 결과 분석을 위한 사용자 피드백 프로필
 */
@Getter
@Setter
public class UserFeedbackProfileDto {
    private SessionMetaDto sessionMeta;
    private List<EvaluationItemDto> evaluationItems;
    private Map<String, Object> additionalFeedback;
}
