package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Phase 2 응답 래퍼
 * ko 브랜치 응답 형식: { "finalized_brief": {...} }
 */
@Getter
@Setter
@NoArgsConstructor
public class Phase2ResponseWrapper {
    @JsonProperty("finalized_brief")
    private FinalizedBriefDto finalizedBrief;
}
