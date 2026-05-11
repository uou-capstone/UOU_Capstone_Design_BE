package io.github.uou_capstone.aiplatform.domain.course.notice.dto;

import io.github.uou_capstone.aiplatform.domain.course.notice.entity.Notice;
import io.github.uou_capstone.aiplatform.domain.course.notice.entity.NoticeCategory;
import io.github.uou_capstone.aiplatform.domain.course.notice.entity.NoticePriority;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class NoticeResponseDto {

    private final Long noticeId;
    private final Long courseId;
    private final Long authorTeacherId;
    private final Long authorUserId;
    private final String authorName;
    private final String title;
    private final String contentMarkdown;
    private final NoticeCategory category;
    private final NoticePriority priority;
    private final boolean pinned;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public NoticeResponseDto(Notice notice) {
        this.noticeId = notice.getId();
        this.courseId = notice.getCourse().getId();
        this.authorTeacherId = notice.getAuthor().getId();
        this.authorUserId = notice.getAuthor().getUser().getId();
        this.authorName = notice.getAuthor().getUser().getFullName();
        this.title = notice.getTitle();
        this.contentMarkdown = notice.getContentMarkdown();
        this.category = notice.getCategory();
        this.priority = notice.getPriority();
        this.pinned = notice.isPinned();
        this.createdAt = notice.getCreatedAt();
        this.updatedAt = notice.getUpdatedAt();
    }
}
