package io.github.uou_capstone.aiplatform.domain.notification.service;

import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.notification.entity.NotificationType;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherNotificationPublisherTest {

    @Mock private NotificationService notificationService;
    @Mock private TeacherNotificationPreferenceService preferenceService;

    @InjectMocks
    private TeacherNotificationPublisher publisher;

    private User teacherUser;
    private User studentUser;
    private Teacher teacher;
    private Course course;

    @BeforeEach
    void setUp() {
        teacherUser = User.builder().email("t@x").password("p").fullName("teacher").build();
        ReflectionTestUtils.setField(teacherUser, "id", 200L);
        teacher = Teacher.builder().schoolName("s").department("d").user(teacherUser).build();
        ReflectionTestUtils.setField(teacher, "id", 10L);

        studentUser = User.builder().email("s@x").password("p").fullName("student").build();
        ReflectionTestUtils.setField(studentUser, "id", 300L);

        course = Course.builder().teacher(teacher).title("c").description("d").invitationCode("code").build();
        ReflectionTestUtils.setField(course, "id", 50L);
    }

    @Test
    void notifyCourseTeacher_publishesWhenActorIsStudent() {
        publisher.notifyCourseTeacher(course, studentUser, NotificationType.DISCUSSION_CREATED,
                "title", "body", "DISCUSSION", 7L);

        verify(notificationService).notify(
                eq(teacherUser), eq(NotificationType.DISCUSSION_CREATED),
                eq("title"), eq("body"), eq("DISCUSSION"), eq(7L));
        // 자기 작업 분기는 발동 안 함
        verify(preferenceService, never()).isSelfActionNotificationsEnabled(anyLong());
    }

    @Test
    void notifyCourseTeacher_skipsRegularButPublishesSelfAction_whenActorIsTheSameTeacher_andOptIn() {
        when(preferenceService.isSelfActionNotificationsEnabled(10L)).thenReturn(true);

        publisher.notifyCourseTeacher(course, teacherUser, NotificationType.DISCUSSION_CREATED,
                "title", "body", "DISCUSSION", 7L);

        // 일반 DISCUSSION_CREATED 는 발행 안 함
        verify(notificationService, never()).notify(
                any(), eq(NotificationType.DISCUSSION_CREATED), anyString(), anyString(), anyString(), anyLong());
        // TEACHER_ACTION_CONFIRMED 로 변환되어 발행
        verify(notificationService).notify(
                eq(teacherUser), eq(NotificationType.TEACHER_ACTION_CONFIRMED),
                eq("title"), eq("body"), eq("DISCUSSION"), eq(7L));
    }

    @Test
    void notifyCourseTeacher_silent_whenActorIsTheSameTeacher_andOptOut() {
        when(preferenceService.isSelfActionNotificationsEnabled(10L)).thenReturn(false);

        publisher.notifyCourseTeacher(course, teacherUser, NotificationType.DISCUSSION_CREATED,
                "title", "body", "DISCUSSION", 7L);

        verify(notificationService, never()).notify(any(), any(), any(), any(), any(), any());
    }

    @Test
    void notifySelfAction_publishesWhenOptIn() {
        when(preferenceService.isSelfActionNotificationsEnabled(10L)).thenReturn(true);

        publisher.notifySelfAction(teacher, "t", "b", "course", 99L);

        verify(notificationService).notify(
                eq(teacherUser), eq(NotificationType.TEACHER_ACTION_CONFIRMED),
                eq("t"), eq("b"), eq("course"), eq(99L));
    }

    @Test
    void notifySelfAction_silentWhenOptOut() {
        when(preferenceService.isSelfActionNotificationsEnabled(10L)).thenReturn(false);

        publisher.notifySelfAction(teacher, "t", "b", "course", 99L);

        verify(notificationService, never()).notify(any(), any(), any(), any(), any(), any());
    }

    @Test
    void notifyCourseTeacher_silentWhenCourseOrTeacherIsNull() {
        publisher.notifyCourseTeacher(null, studentUser, NotificationType.DISCUSSION_CREATED,
                "t", "b", "DISCUSSION", 1L);

        Course noTeacher = Course.builder().title("c").description("d").invitationCode("c2").build();
        publisher.notifyCourseTeacher(noTeacher, studentUser, NotificationType.DISCUSSION_CREATED,
                "t", "b", "DISCUSSION", 1L);

        verify(notificationService, never()).notify(any(), any(), any(), any(), any(), any());
    }
}
