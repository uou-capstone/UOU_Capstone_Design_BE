package io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Criteria Assistant 추천 요청 — 모든 필드 선택. 미지정 시 Spring 측 기본값 적용.
 */
@Getter
@Setter
@NoArgsConstructor
public class CriteriaAssistantRequest {

    @Min(1) @Max(10)
    private Integer desiredCount;

    @Size(max = 10)
    private String language;
}
