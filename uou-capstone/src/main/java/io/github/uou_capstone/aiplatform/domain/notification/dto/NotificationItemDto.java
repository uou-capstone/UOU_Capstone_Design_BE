package io.github.uou_capstone.aiplatform.domain.notification.dto;

import io.github.uou_capstone.aiplatform.domain.notification.entity.Notification;
import io.github.uou_capstone.aiplatform.domain.notification.entity.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@Getter
public class NotificationItemDto {

    private final Long notificationId;
    private final NotificationType type;
    private final String title;
    private final String body;
    private final String resourceType;
    private final Long resourceId;
    private final boolean read;
    @Schema(type = "string", format = "date-time", example = "2026-05-22T12:34:56Z")
    private final OffsetDateTime createdAt;

    public NotificationItemDto(Notification n) {
        this.notificationId = n.getId();
        this.type = n.getType();
        this.title = n.getTitle();
        this.body = n.getBody();
        this.resourceType = n.getResourceType();
        this.resourceId = n.getResourceId();
        this.read = n.isRead();
        this.createdAt = toUtcOffset(n.getCreatedAt());
    }

    /**
     * SSE payload (Map shape) — FE 가 단일 스키마로 처리하도록 REST 응답과 동일 키 셋을 사용한다.
     */
    public Map<String, Object> toSsePayload() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("notificationId", notificationId);
        m.put("type", type);
        m.put("title", title);
        m.put("body", body);
        m.put("resourceType", resourceType);
        m.put("resourceId", resourceId);
        m.put("read", read);
        m.put("createdAt", createdAt);
        return m;
    }

    private static OffsetDateTime toUtcOffset(LocalDateTime value) {
        return (value != null ? value : LocalDateTime.now(ZoneOffset.UTC))
                .atOffset(ZoneOffset.UTC);
    }
}
