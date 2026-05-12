package io.github.uou_capstone.aiplatform.domain.notification.dto;

import io.github.uou_capstone.aiplatform.domain.notification.entity.TeacherNotificationPreference;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 교사 알림 설정 응답/요청 통합 DTO.
 * GET 응답 + PATCH 요청에 모두 사용. 필드 1개라 통합으로 충분.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TeacherNotificationPreferenceDto {

    private boolean includeSelfActionNotifications;

    public static TeacherNotificationPreferenceDto from(TeacherNotificationPreference entity) {
        return new TeacherNotificationPreferenceDto(entity.isIncludeSelfActionNotifications());
    }
}
