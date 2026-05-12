package io.github.uou_capstone.aiplatform.domain.notification.service;

import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.notification.entity.NotificationType;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 교사 알림 단일 경유 헬퍼.
 *
 * <p>도메인 서비스들이 직접 NotificationService 를 호출하는 대신 이 Publisher 를 거치면
 * (1) 교사 본인이 발생시킨 학생/시스템 이벤트 중복 알림 차단,
 * (2) TEACHER_ACTION_CONFIRMED 의 설정 ON/OFF 분기를 단일 지점에서 처리할 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeacherNotificationPublisher {

    private final NotificationService notificationService;
    private final TeacherNotificationPreferenceService preferenceService;

    /**
     * 강의실 담당 교사에게 학생/시스템 이벤트 알림.
     *
     * @param course   강의실 (담당 교사 → recipient)
     * @param actor    이벤트를 일으킨 사용자. 교사 본인이 일으킨 경우 본 알림은 발행되지 않고,
     *                 대신 자기 작업 확인 알림(TEACHER_ACTION_CONFIRMED)이 설정 ON 일 때만 발행된다.
     * @param type     알림 타입
     * @param title    제목
     * @param body     본문
     * @param resourceType 리소스 분류 (예: "DISCUSSION", "NOTICE", "ASSESSMENT", "EXAM", "material")
     * @param resourceId   리소스 ID
     */
    public void notifyCourseTeacher(Course course,
                                    User actor,
                                    NotificationType type,
                                    String title,
                                    String body,
                                    String resourceType,
                                    Long resourceId) {
        if (course == null || course.getTeacher() == null) {
            log.debug("notifyCourseTeacher skipped: course or teacher is null");
            return;
        }
        Teacher teacher = course.getTeacher();
        User recipient = teacher.getUser();
        if (recipient == null) {
            log.debug("notifyCourseTeacher skipped: teacher.user is null teacherId={}", teacher.getId());
            return;
        }

        boolean actorIsThisTeacher = actor != null && recipient.getId().equals(actor.getId());

        if (actorIsThisTeacher) {
            // 교사 본인이 일으킨 학생/시스템 이벤트 → TEACHER_ACTION_CONFIRMED 로 변환 후 설정 ON 시에만 발행
            notifySelfAction(teacher, title, body, resourceType, resourceId);
            return;
        }

        notificationService.notify(recipient, type, title, body, resourceType, resourceId);
    }

    /**
     * 교사 본인 작업 확인 알림. preference.includeSelfActionNotifications=true 일 때만 발행.
     */
    public void notifySelfAction(Teacher teacher,
                                 String title,
                                 String body,
                                 String resourceType,
                                 Long resourceId) {
        if (teacher == null || teacher.getUser() == null) return;

        if (!preferenceService.isSelfActionNotificationsEnabled(teacher.getId())) {
            return;
        }
        notificationService.notify(
                teacher.getUser(),
                NotificationType.TEACHER_ACTION_CONFIRMED,
                title,
                body,
                resourceType,
                resourceId
        );
    }
}
