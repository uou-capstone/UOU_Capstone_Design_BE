package io.github.uou_capstone.aiplatform.domain.course.notice.entity;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 공지 댓글. 학생/교사 모두 작성 가능. 1단계 답글만 허용 (parentComment 의 parentComment 는 null 강제).
 *
 * <p>{@link OnDelete} 는 Hibernate DDL 생성 시 ON DELETE CASCADE FK 를 박아 H2 테스트 환경에서도
 * 부모 삭제 시 자식이 함께 삭제되도록 한다 (운영 MySQL DDL 과 동작 일치).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "notice_comments",
        indexes = {
                @Index(name = "idx_nc_notice", columnList = "notice_id, created_at")
        })
public class NoticeComment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notice_comment_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notice_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Notice notice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_user_id", nullable = false)
    private User author;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_comment_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private NoticeComment parentComment;

    @Column(name = "content_markdown", columnDefinition = "TEXT", nullable = false)
    private String contentMarkdown;

    @Builder
    public NoticeComment(Notice notice,
                         User author,
                         NoticeComment parentComment,
                         String contentMarkdown) {
        this.notice = notice;
        this.author = author;
        this.parentComment = parentComment;
        this.contentMarkdown = contentMarkdown;
    }

    public void updateContent(String contentMarkdown) {
        if (contentMarkdown != null && !contentMarkdown.isBlank()) {
            this.contentMarkdown = contentMarkdown;
        }
    }

    public boolean isReply() {
        return parentComment != null;
    }
}
