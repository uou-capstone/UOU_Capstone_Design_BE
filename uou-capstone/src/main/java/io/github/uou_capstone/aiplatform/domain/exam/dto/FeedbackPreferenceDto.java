package io.github.uou_capstone.aiplatform.domain.exam.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 피드백 설정
 */
@Getter
@Setter
public class FeedbackPreferenceDto {
    private String strictness; // "lenient", "moderate", "strict"
    private String explanationDepth; // "brief", "detailed", "comprehensive"
}
