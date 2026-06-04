package io.github.uou_capstone.aiplatform.domain.notification.repository;

import io.github.uou_capstone.aiplatform.domain.notification.entity.TeacherNotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TeacherNotificationPreferenceRepository
        extends JpaRepository<TeacherNotificationPreference, Long> {

    Optional<TeacherNotificationPreference> findByTeacherId(Long teacherId);
}
