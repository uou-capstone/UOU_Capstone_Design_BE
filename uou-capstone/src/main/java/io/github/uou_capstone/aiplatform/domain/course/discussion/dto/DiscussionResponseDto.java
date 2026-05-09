package io.github.uou_capstone.aiplatform.domain.course.discussion.dto;

import io.github.uou_capstone.aiplatform.domain.course.discussion.entity.Discussion;
import io.github.uou_capstone.aiplatform.domain.course.discussion.entity.DiscussionCategory;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class DiscussionResponseDto {

    private final Long discussionId;
    private final Long courseId;
    private final Long authorUserId;
    private final String authorName;
    private final String title;
    private final String contentMarkdown;
    private final DiscussionCategory category;
    private final boolean pinned;
    private final boolean allowComments;
    private final int viewCount;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public DiscussionResponseDto(Discussion d) {
        this.discussionId = d.getId();
        this.courseId = d.getCourse().getId();
        this.authorUserId = d.getAuthor().getId();
        this.authorName = d.getAuthor().getFullName();
        this.title = d.getTitle();
        this.contentMarkdown = d.getContentMarkdown();
        this.category = d.getCategory();
        this.pinned = d.isPinned();
        this.allowComments = d.isAllowComments();
        this.viewCount = d.getViewCount();
        this.createdAt = d.getCreatedAt();
        this.updatedAt = d.getUpdatedAt();
    }
}
