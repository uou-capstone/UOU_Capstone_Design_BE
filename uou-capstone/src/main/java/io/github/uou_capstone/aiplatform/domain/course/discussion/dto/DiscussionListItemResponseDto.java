package io.github.uou_capstone.aiplatform.domain.course.discussion.dto;

import io.github.uou_capstone.aiplatform.domain.course.discussion.entity.Discussion;
import io.github.uou_capstone.aiplatform.domain.course.discussion.entity.DiscussionCategory;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class DiscussionListItemResponseDto {

    private final Long discussionId;
    private final Long authorUserId;
    private final String authorName;
    private final String title;
    private final DiscussionCategory category;
    private final boolean pinned;
    private final boolean allowComments;
    private final int viewCount;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public DiscussionListItemResponseDto(Discussion d) {
        this.discussionId = d.getId();
        this.authorUserId = d.getAuthor().getId();
        this.authorName = d.getAuthor().getFullName();
        this.title = d.getTitle();
        this.category = d.getCategory();
        this.pinned = d.isPinned();
        this.allowComments = d.isAllowComments();
        this.viewCount = d.getViewCount();
        this.createdAt = d.getCreatedAt();
        this.updatedAt = d.getUpdatedAt();
    }
}
