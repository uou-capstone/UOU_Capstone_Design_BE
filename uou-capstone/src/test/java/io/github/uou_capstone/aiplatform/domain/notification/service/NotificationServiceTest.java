package io.github.uou_capstone.aiplatform.domain.notification.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.notification.entity.Notification;
import io.github.uou_capstone.aiplatform.domain.notification.entity.NotificationType;
import io.github.uou_capstone.aiplatform.domain.notification.repository.NotificationRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private NotificationStreamRegistry streamRegistry;
    @Mock private CurrentUserResolver currentUserResolver;

    @InjectMocks
    private NotificationService notificationService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().email("u@x").password("p").fullName("u").build();
        ReflectionTestUtils.setField(user, "id", 7L);
    }

    @Test
    void notify_savesNotification() {
        Notification persisted = Notification.builder()
                .user(user).type(NotificationType.COURSE_JOIN_APPROVED)
                .title("t").body("b").resourceType("course").resourceId(1L).build();
        ReflectionTestUtils.setField(persisted, "id", 99L);
        when(notificationRepository.save(any(Notification.class))).thenReturn(persisted);

        Notification saved = notificationService.notify(user,
                NotificationType.COURSE_JOIN_APPROVED, "t", "b", "course", 1L);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getUser()).isEqualTo(user);
        assertThat(captor.getValue().getType()).isEqualTo(NotificationType.COURSE_JOIN_APPROVED);
        assertThat(saved).isEqualTo(persisted);
    }

    @Test
    void notify_pushesToStreamRegistry_whenNoTransactionActive() {
        Notification persisted = Notification.builder()
                .user(user).type(NotificationType.COURSE_JOIN_APPROVED)
                .title("t").body("b").resourceType("course").resourceId(1L).build();
        ReflectionTestUtils.setField(persisted, "id", 99L);
        when(notificationRepository.save(any(Notification.class))).thenReturn(persisted);

        notificationService.notify(user, NotificationType.COURSE_JOIN_APPROVED,
                "t", "b", "course", 1L);

        verify(streamRegistry).push(eq(7L), any());
    }

    @Test
    void getUnreadCount_returnsRepositoryCount() {
        when(currentUserResolver.getUser()).thenReturn(user);
        when(notificationRepository.countByUserIdAndReadAtIsNull(7L)).thenReturn(3L);

        assertThat(notificationService.getUnreadCount()).isEqualTo(3L);
    }

    @Test
    void markAsRead_marksTargetNotification() {
        Notification target = Notification.builder()
                .user(user).type(NotificationType.COURSE_JOIN_APPROVED)
                .title("t").body("b").resourceType("course").resourceId(1L).build();
        ReflectionTestUtils.setField(target, "id", 42L);
        when(currentUserResolver.getUser()).thenReturn(user);
        when(notificationRepository.findByIdAndUserId(42L, 7L)).thenReturn(Optional.of(target));

        notificationService.markAsRead(42L);

        assertThat(target.isRead()).isTrue();
    }

    @Test
    void markAsRead_throwsWhenNotFound() {
        when(currentUserResolver.getUser()).thenReturn(user);
        when(notificationRepository.findByIdAndUserId(anyLong(), anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.markAsRead(999L))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void markAllAsRead_callsBulkUpdate() {
        when(currentUserResolver.getUser()).thenReturn(user);

        notificationService.markAllAsRead();

        verify(notificationRepository).markAllAsReadByUserId(eq(7L), any(LocalDateTime.class));
    }
}
