package io.github.uou_capstone.aiplatform.domain.course.discussion.dto;

import io.github.uou_capstone.aiplatform.domain.course.discussion.entity.DiscussionComment;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class DiscussionCommentResponseDto {

    private final Long commentId;
    private final Long discussionId;
    private final Long authorUserId;
    private final String authorName;
    private final Long parentCommentId;
    private final String contentMarkdown;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public DiscussionCommentResponseDto(DiscussionComment c) {
        this.commentId = c.getId();
        this.discussionId = c.getDiscussion().getId();
        this.authorUserId = c.getAuthor().getId();
        this.authorName = c.getAuthor().getFullName();
        this.parentCommentId = c.getParentComment() != null ? c.getParentComment().getId() : null;
        this.contentMarkdown = c.getContentMarkdown();
        this.createdAt = c.getCreatedAt();
        this.updatedAt = c.getUpdatedAt();
    }
}
