package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Phase 1 응답 래퍼
 * ko 브랜치 응답 형식: { "draft_plan": {...} }
 */
@Getter
@Setter
@NoArgsConstructor
public class Phase1ResponseWrapper {
    @JsonProperty("draft_plan")
    private DraftPlanDto draftPlan;
}
