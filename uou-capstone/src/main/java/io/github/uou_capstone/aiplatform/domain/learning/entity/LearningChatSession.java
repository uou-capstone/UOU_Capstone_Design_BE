package io.github.uou_capstone.aiplatform.domain.learning.entity;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "learning_chat_sessions",
        indexes = {
                @Index(name = "idx_learning_chat_session_user_lecture", columnList = "user_id, lecture_id"),
                @Index(name = "idx_learning_chat_session_last_message", columnList = "last_message_at")
        })
public class LearningChatSession extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_session_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lecture_id", nullable = false)
    private Lecture lecture;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(length = 100)
    private String title;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Builder
    public LearningChatSession(Lecture lecture, User user, String title) {
        this.lecture = lecture;
        this.user = user;
        this.title = title;
    }

    public void touch(LocalDateTime now) {
        this.lastMessageAt = now;
    }

    public void setTitleIfBlank(String title) {
        if ((this.title == null || this.title.isBlank()) && title != null && !title.isBlank()) {
            this.title = title.length() > 100 ? title.substring(0, 100) : title;
        }
    }

    public void markEnded(LocalDateTime now) {
        this.endedAt = now;
        touch(now);
    }
}
