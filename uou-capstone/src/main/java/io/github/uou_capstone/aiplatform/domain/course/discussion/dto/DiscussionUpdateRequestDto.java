package io.github.uou_capstone.aiplatform.domain.course.discussion.dto;

import io.github.uou_capstone.aiplatform.domain.course.discussion.entity.DiscussionCategory;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * PATCH 부분 갱신 — 모든 필드 nullable. Boolean 은 wrapper 사용.
 */
@Getter
@NoArgsConstructor
public class DiscussionUpdateRequestDto {

    @Size(max = 120)
    private String title;

    @Size(max = 12000)
    private String contentMarkdown;

    private DiscussionCategory category;
    private Boolean pinned;
    private Boolean allowComments;
}
