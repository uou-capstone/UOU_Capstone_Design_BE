package io.github.uou_capstone.aiplatform.domain.course.notice.entity;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 강의실 공지사항. 교사가 작성하며 ACTIVE 수강생 전체에게 알림 발송 트리거.
 *
 * <p>주의: 권한 비교 시 author 가 Teacher 엔티티이므로 User 비교는
 * {@code notice.getAuthor().getUser().getId()} 를 사용해야 한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "notices",
        indexes = {
                @Index(name = "idx_notices_course_pinned_created",
                        columnList = "course_id, pinned DESC, created_at DESC")
        })
public class Notice extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notice_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_teacher_id", nullable = false)
    private Teacher author;

    @Column(name = "title", length = 100, nullable = false)
    private String title;

    @Column(name = "content_markdown", columnDefinition = "TEXT", nullable = false)
    private String contentMarkdown;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 20, nullable = false)
    private NoticeCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", length = 20, nullable = false)
    private NoticePriority priority;

    @Column(name = "pinned", nullable = false)
    private boolean pinned;

    @OneToMany(mappedBy = "notice", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<NoticeComment> comments = new ArrayList<>();

    @Builder
    public Notice(Course course,
                  Teacher author,
                  String title,
                  String contentMarkdown,
                  NoticeCategory category,
                  NoticePriority priority,
                  Boolean pinned) {
        this.course = course;
        this.author = author;
        this.title = title;
        this.contentMarkdown = contentMarkdown;
        this.category = category != null ? category : NoticeCategory.GENERAL;
        this.priority = priority != null ? priority : NoticePriority.NORMAL;
        this.pinned = pinned != null && pinned;
    }

    public void update(String title,
                       String contentMarkdown,
                       NoticeCategory category,
                       NoticePriority priority,
                       Boolean pinned) {
        if (title != null && !title.isBlank()) this.title = title;
        if (contentMarkdown != null) this.contentMarkdown = contentMarkdown;
        if (category != null) this.category = category;
        if (priority != null) this.priority = priority;
        if (pinned != null) this.pinned = pinned;
    }
}
