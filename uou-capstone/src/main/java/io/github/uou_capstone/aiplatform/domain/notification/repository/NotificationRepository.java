package io.github.uou_capstone.aiplatform.domain.notification.repository;

import io.github.uou_capstone.aiplatform.domain.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByUserId(Long userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(Long userId);

    Optional<Notification> findByIdAndUserId(Long id, Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Notification n
               SET n.readAt = :now
             WHERE n.user.id = :userId AND n.readAt IS NULL
            """)
    int markAllAsReadByUserId(@Param("userId") Long userId,
                              @Param("now") LocalDateTime now);
}
