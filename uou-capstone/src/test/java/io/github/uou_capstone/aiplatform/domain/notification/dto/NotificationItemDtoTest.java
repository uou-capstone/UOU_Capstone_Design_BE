package io.github.uou_capstone.aiplatform.domain.notification.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.uou_capstone.aiplatform.domain.notification.entity.Notification;
import io.github.uou_capstone.aiplatform.domain.notification.entity.NotificationType;
import io.github.uou_capstone.aiplatform.domain.user.entity.Role;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationItemDtoTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    void serializesCreatedAtAsCamelCaseIsoOffsetDateTime() throws Exception {
        NotificationItemDto dto = new NotificationItemDto(notificationWithCreatedAt(
                LocalDateTime.of(2026, 5, 22, 12, 34, 56)));

        String json = objectMapper.writeValueAsString(dto);

        assertThat(json).contains("\"createdAt\":\"2026-05-22T12:34:56Z\"");
        assertThat(json).doesNotContain("created_at");
    }

    @Test
    void ssePayloadUsesSameCreatedAtFieldAndType() {
        NotificationItemDto dto = new NotificationItemDto(notificationWithCreatedAt(
                LocalDateTime.of(2026, 5, 22, 12, 34, 56)));

        Map<String, Object> payload = dto.toSsePayload();

        assertThat(payload).containsKey("createdAt");
        assertThat(payload.get("createdAt"))
                .isEqualTo(OffsetDateTime.of(2026, 5, 22, 12, 34, 56, 0, ZoneOffset.UTC));
    }

    @Test
    void createdAtFallsBackWhenEntityAuditValueIsNull() {
        NotificationItemDto dto = new NotificationItemDto(notificationWithCreatedAt(null));

        assertThat(dto.getCreatedAt()).isNotNull();
        assertThat(dto.toSsePayload().get("createdAt")).isNotNull();
    }

    private Notification notificationWithCreatedAt(LocalDateTime createdAt) {
        User user = User.builder()
                .email("user@example.com")
                .password("p")
                .fullName("user")
                .role(Role.STUDENT)
                .build();
        Notification notification = Notification.builder()
                .user(user)
                .type(NotificationType.COURSE_JOIN_APPROVED)
                .title("title")
                .body("body")
                .resourceType("course")
                .resourceId(1L)
                .build();
        ReflectionTestUtils.setField(notification, "id", 100L);
        ReflectionTestUtils.setField(notification, "createdAt", createdAt);
        return notification;
    }
}
