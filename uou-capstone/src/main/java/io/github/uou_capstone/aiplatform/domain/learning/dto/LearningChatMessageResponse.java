package io.github.uou_capstone.aiplatform.domain.learning.dto;

import io.github.uou_capstone.aiplatform.domain.learning.entity.LearningChatMessage;
import io.github.uou_capstone.aiplatform.domain.learning.entity.LearningChatMessageRole;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Getter
public class LearningChatMessageResponse {

    private final Long messageId;
    private final LearningChatMessageRole role;
    private final String content;
    private final Integer pageNumber;
    @Schema(type = "string", format = "date-time")
    private final OffsetDateTime createdAt;

    public LearningChatMessageResponse(LearningChatMessage message) {
        this.messageId = message.getId();
        this.role = message.getRole();
        this.content = message.getContent();
        this.pageNumber = message.getPageNumber();
        this.createdAt = toUtcOffset(message.getCreatedAt());
    }

    private static OffsetDateTime toUtcOffset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
