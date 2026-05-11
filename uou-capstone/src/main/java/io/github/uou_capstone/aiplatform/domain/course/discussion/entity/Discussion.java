package io.github.uou_capstone.aiplatform.domain.course.discussion.entity;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 토론/자유게시판 게시글. 학생/교사 모두 작성 가능. {@link Discussion#author} 가 User 라
 * 알림 호출 시 그대로 사용 가능 ({@code discussion.getAuthor()}).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "discussions",
        indexes = {
                @Index(name = "idx_disc_course_pinned_created",
                        columnList = "course_id, pinned DESC, created_at DESC")
        })
public class Discussion extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "discussion_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_user_id", nullable = false)
    private User author;

    @Column(name = "title", length = 120, nullable = false)
    private String title;

    @Column(name = "content_markdown", columnDefinition = "TEXT", nullable = false)
    private String contentMarkdown;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 20, nullable = false)
    private DiscussionCategory category;

    @Column(name = "pinned", nullable = false)
    private boolean pinned;

    @Column(name = "allow_comments", nullable = false)
    private boolean allowComments;

    @Column(name = "view_count", nullable = false)
    private int viewCount;

    @OneToMany(mappedBy = "discussion", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DiscussionComment> comments = new ArrayList<>();

    @Builder
    public Discussion(Course course,
                      User author,
                      String title,
                      String contentMarkdown,
                      DiscussionCategory category,
                      Boolean pinned,
                      Boolean allowComments) {
        this.course = course;
        this.author = author;
        this.title = title;
        this.contentMarkdown = contentMarkdown;
        this.category = category != null ? category : DiscussionCategory.FREE;
        this.pinned = pinned != null && pinned;
        this.allowComments = allowComments == null || allowComments; // null → true (default 허용)
        this.viewCount = 0;
    }

    public void update(String title,
                       String contentMarkdown,
                       DiscussionCategory category,
                       Boolean pinned,
                       Boolean allowComments) {
        if (title != null && !title.isBlank()) this.title = title;
        if (contentMarkdown != null) this.contentMarkdown = contentMarkdown;
        if (category != null) this.category = category;
        if (pinned != null) this.pinned = pinned;
        if (allowComments != null) this.allowComments = allowComments;
    }
}
