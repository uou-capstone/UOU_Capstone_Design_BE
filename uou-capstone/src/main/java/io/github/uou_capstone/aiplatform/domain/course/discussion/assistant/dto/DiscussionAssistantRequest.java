package io.github.uou_capstone.aiplatform.domain.course.discussion.assistant.dto;

import io.github.uou_capstone.aiplatform.domain.course.discussion.entity.DiscussionCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Discussion AI Assistant 요청.
 *
 * <p>FastAPI {@code POST /bridge/discussion_assistant_stream} 호출 시 Spring 측이 보강하는 필드:
 * {@code courseId}, {@code courseName}, {@code recentDiscussions[]}.
 */
@Getter
@Setter
@NoArgsConstructor
public class DiscussionAssistantRequest {

    @NotBlank
    @Size(max = 500)
    private String topic;

    /** null 허용 — 카테고리 미지정으로 처리. */
    private DiscussionCategory category;

    /** 부분 작성한 본문 (이어쓰기 모드). null/blank 허용. */
    @Size(max = 10_000)
    private String previousDraft;
}
