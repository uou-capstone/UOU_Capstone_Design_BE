package io.github.uou_capstone.aiplatform.domain.notification.service;

import io.github.uou_capstone.aiplatform.domain.notification.dto.TeacherNotificationPreferenceDto;
import io.github.uou_capstone.aiplatform.domain.notification.entity.TeacherNotificationPreference;
import io.github.uou_capstone.aiplatform.domain.notification.repository.TeacherNotificationPreferenceRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TeacherNotificationPreferenceService {

    private final TeacherNotificationPreferenceRepository preferenceRepository;
    private final CurrentUserResolver currentUserResolver;

    /**
     * 현재 교사의 알림 설정 조회. row가 없으면 기본값(false)으로 lazy-create.
     * CurrentUserResolver.getTeacher() 가 비-교사 호출 시 BusinessException 으로 차단.
     */
    @Transactional
    public TeacherNotificationPreferenceDto getOrCreate() {
        Teacher teacher = currentUserResolver.getTeacher();
        TeacherNotificationPreference pref = preferenceRepository.findByTeacherId(teacher.getId())
                .orElseGet(() -> preferenceRepository.save(
                        TeacherNotificationPreference.builder()
                                .teacher(teacher)
                                .includeSelfActionNotifications(false)
                                .build()
                ));
        return TeacherNotificationPreferenceDto.from(pref);
    }

    @Transactional
    public TeacherNotificationPreferenceDto update(TeacherNotificationPreferenceDto dto) {
        Teacher teacher = currentUserResolver.getTeacher();
        TeacherNotificationPreference pref = preferenceRepository.findByTeacherId(teacher.getId())
                .orElseGet(() -> preferenceRepository.save(
                        TeacherNotificationPreference.builder()
                                .teacher(teacher)
                                .includeSelfActionNotifications(false)
                                .build()
                ));
        pref.setIncludeSelfActionNotifications(dto.isIncludeSelfActionNotifications());
        return TeacherNotificationPreferenceDto.from(pref);
    }

    /**
     * Publisher 가 본인 작업 알림 발행 여부 판단 시 사용. 외부 호출용 read-only.
     * row 없으면 기본값(false) 반환 (lazy-create 안 함 — 단순 조회 호출에서 부작용 방지).
     */
    @Transactional(readOnly = true)
    public boolean isSelfActionNotificationsEnabled(Long teacherId) {
        return preferenceRepository.findByTeacherId(teacherId)
                .map(TeacherNotificationPreference::isIncludeSelfActionNotifications)
                .orElse(false);
    }
}
