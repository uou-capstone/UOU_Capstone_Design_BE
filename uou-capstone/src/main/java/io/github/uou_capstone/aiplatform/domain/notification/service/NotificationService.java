package io.github.uou_capstone.aiplatform.domain.notification.service;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.common.web.PageableSupport;
import io.github.uou_capstone.aiplatform.domain.notification.dto.NotificationItemDto;
import io.github.uou_capstone.aiplatform.domain.notification.entity.Notification;
import io.github.uou_capstone.aiplatform.domain.notification.entity.NotificationType;
import io.github.uou_capstone.aiplatform.domain.notification.repository.NotificationRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final Set<String> SORT_WHITELIST = Set.of("createdAt");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final NotificationRepository notificationRepository;
    private final NotificationStreamRegistry streamRegistry;
    private final CurrentUserResolver currentUserResolver;

    /**
     * 알림 발행. 호출자의 트랜잭션 안에서 실행되며, 실시간 SSE push 는 afterCommit 후에만 수행한다
     * (커밋 실패 시 학생에게 false-push 가 가지 않도록).
     */
    @Transactional
    public Notification notify(User user,
                               NotificationType type,
                               String title,
                               String body,
                               String resourceType,
                               Long resourceId) {
        Notification saved = notificationRepository.save(
                Notification.builder()
                        .user(user)
                        .type(type)
                        .title(title)
                        .body(body)
                        .resourceType(resourceType)
                        .resourceId(resourceId)
                        .build()
        );

        Long userId = user.getId();
        NotificationItemDto dto = new NotificationItemDto(saved);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    streamRegistry.push(userId, dto);
                }
            });
        } else {
            streamRegistry.push(userId, dto);
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationItemDto> getMyNotifications(Pageable rawPageable) {
        Pageable pageable = PageableSupport.validate(rawPageable, SORT_WHITELIST, DEFAULT_SORT);
        Long userId = currentUserResolver.getUserId();
        Page<Notification> page = notificationRepository.findByUserId(userId, pageable);
        return PageResponse.of(page.map(NotificationItemDto::new));
    }

    @Transactional(readOnly = true)
    public long getUnreadCount() {
        Long userId = currentUserResolver.getUserId();
        return notificationRepository.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public void markAsRead(Long notificationId) {
        Long userId = currentUserResolver.getUserId();
        Notification n = notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        n.markRead(LocalDateTime.now());
    }

    @Transactional
    public void markAllAsRead() {
        Long userId = currentUserResolver.getUserId();
        notificationRepository.markAllAsReadByUserId(userId, LocalDateTime.now());
    }
}
