package io.github.uou_capstone.aiplatform.domain.notification.entity;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
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
@Table(name = "notifications",
        indexes = {
                @Index(name = "idx_notification_user_read", columnList = "user_id, read_at"),
                @Index(name = "idx_notification_user_created", columnList = "user_id, created_at")
        })
public class Notification extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private NotificationType type;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 1000)
    private String body;

    @Column(name = "resource_type", length = 40)
    private String resourceType;

    @Column(name = "resource_id")
    private Long resourceId;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Builder
    public Notification(User user,
                        NotificationType type,
                        String title,
                        String body,
                        String resourceType,
                        Long resourceId) {
        this.user = user;
        this.type = type;
        this.title = title;
        this.body = body;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }

    public boolean isRead() {
        return readAt != null;
    }

    public void markRead(LocalDateTime now) {
        if (this.readAt == null) {
            this.readAt = now;
        }
    }
}
