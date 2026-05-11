package io.github.uou_capstone.aiplatform.domain.course.discussion.entity;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "discussion_comments",
        indexes = {
                @Index(name = "idx_dc_disc", columnList = "discussion_id, created_at")
        })
public class DiscussionComment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "discussion_comment_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "discussion_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Discussion discussion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_user_id", nullable = false)
    private User author;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_comment_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private DiscussionComment parentComment;

    @Column(name = "content_markdown", columnDefinition = "TEXT", nullable = false)
    private String contentMarkdown;

    @Builder
    public DiscussionComment(Discussion discussion,
                             User author,
                             DiscussionComment parentComment,
                             String contentMarkdown) {
        this.discussion = discussion;
        this.author = author;
        this.parentComment = parentComment;
        this.contentMarkdown = contentMarkdown;
    }

    public void updateContent(String contentMarkdown) {
        if (contentMarkdown != null && !contentMarkdown.isBlank()) {
            this.contentMarkdown = contentMarkdown;
        }
    }
}
