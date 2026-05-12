package io.github.uou_capstone.aiplatform.domain.notification.entity;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 교사 알림 수신 설정.
 *
 * <p>현재는 단일 토글: includeSelfActionNotifications.
 * 학생/시스템 이벤트는 항상 수신하므로 별도 토글 없음. 자기 작업 확인 알림만 opt-in.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "teacher_notification_preferences",
        uniqueConstraints = @UniqueConstraint(name = "uq_tnp_teacher", columnNames = "teacher_id"))
public class TeacherNotificationPreference extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pref_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id", nullable = false)
    private Teacher teacher;

    @Column(name = "include_self_action_notifications", nullable = false)
    private boolean includeSelfActionNotifications;

    @Builder
    public TeacherNotificationPreference(Teacher teacher, boolean includeSelfActionNotifications) {
        this.teacher = teacher;
        this.includeSelfActionNotifications = includeSelfActionNotifications;
    }

    public void setIncludeSelfActionNotifications(boolean value) {
        this.includeSelfActionNotifications = value;
    }
}
