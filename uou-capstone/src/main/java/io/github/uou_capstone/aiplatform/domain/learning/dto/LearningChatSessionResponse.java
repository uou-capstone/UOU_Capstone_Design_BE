package io.github.uou_capstone.aiplatform.domain.learning.dto;

import io.github.uou_capstone.aiplatform.domain.learning.entity.LearningChatSession;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Getter
public class LearningChatSessionResponse {

    private final Long chatSessionId;
    private final Long lectureId;
    private final String title;
    @Schema(type = "string", format = "date-time")
    private final OffsetDateTime lastMessageAt;
    @Schema(type = "string", format = "date-time")
    private final OffsetDateTime endedAt;
    @Schema(type = "string", format = "date-time")
    private final OffsetDateTime createdAt;

    public LearningChatSessionResponse(LearningChatSession session) {
        this.chatSessionId = session.getId();
        this.lectureId = session.getLecture().getId();
        this.title = session.getTitle();
        this.lastMessageAt = toUtcOffset(session.getLastMessageAt());
        this.endedAt = toUtcOffset(session.getEndedAt());
        this.createdAt = toUtcOffset(session.getCreatedAt());
    }

    private static OffsetDateTime toUtcOffset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
