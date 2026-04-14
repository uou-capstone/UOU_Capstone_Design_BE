package io.github.uou_capstone.aiplatform.domain.learning.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 학습 세션 이벤트 요청 DTO
 *
 * FastAPI OrchestrationEngine의 AppEvent와 동일한 구조.
 * type 필드 외 나머지 필드는 이벤트 유형에 따라 자유롭게 추가될 수 있으므로
 * JsonAnySetter/JsonAnyGetter로 수집하여 FastAPI에 그대로 전달한다.
 *
 * 주요 이벤트 타입:
 * - SESSION_ENTERED
 * - START_EXPLANATION_DECISION
 * - PAGE_CHANGED
 * - USER_MESSAGE (payload 권장 키: {@code question} — llm_multi_agent / Bridge·Session 계약. {@code text}는 Spring에서 {@code question}으로 보강 가능)
 * - QUIZ_DECISION
 * - QUIZ_TYPE_SELECTED
 * - QUIZ_SUBMITTED
 * - REVIEW_DECISION
 * - RETEST_DECISION
 * - NEXT_PAGE_DECISION
 * - SAVE_AND_EXIT
 */
@Getter
@Setter
@NoArgsConstructor
public class SessionEventRequest {

    @NotBlank(message = "이벤트 타입은 필수입니다.")
    private String type;

    private final Map<String, Object> extra = new LinkedHashMap<>();

    @JsonAnySetter
    public void setExtra(String key, Object value) {
        extra.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, Object> getExtra() {
        return extra;
    }

    public Map<String, Object> toPayload() {
        return new LinkedHashMap<>(extra);
    }
}
