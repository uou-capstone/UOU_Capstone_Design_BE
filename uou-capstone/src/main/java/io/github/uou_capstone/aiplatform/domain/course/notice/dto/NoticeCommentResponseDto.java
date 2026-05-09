package io.github.uou_capstone.aiplatform.domain.course.notice.dto;

import io.github.uou_capstone.aiplatform.domain.course.notice.entity.NoticeComment;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class NoticeCommentResponseDto {

    private final Long commentId;
    private final Long noticeId;
    private final Long authorUserId;
    private final String authorName;
    private final Long parentCommentId;
    private final String contentMarkdown;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public NoticeCommentResponseDto(NoticeComment comment) {
        this.commentId = comment.getId();
        this.noticeId = comment.getNotice().getId();
        this.authorUserId = comment.getAuthor().getId();
        this.authorName = comment.getAuthor().getFullName();
        this.parentCommentId = comment.getParentComment() != null ? comment.getParentComment().getId() : null;
        this.contentMarkdown = comment.getContentMarkdown();
        this.createdAt = comment.getCreatedAt();
        this.updatedAt = comment.getUpdatedAt();
    }
}
