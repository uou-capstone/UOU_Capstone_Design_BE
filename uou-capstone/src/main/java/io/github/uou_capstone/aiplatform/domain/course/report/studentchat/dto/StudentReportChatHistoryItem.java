package io.github.uou_capstone.aiplatform.domain.course.report.studentchat.dto;

import io.github.uou_capstone.aiplatform.domain.course.report.studentchat.entity.StudentReportChatMessage;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class StudentReportChatHistoryItem {

    private final Long sessionId;
    private final String role;
    private final String message;
    private final LocalDateTime createdAt;

    public StudentReportChatHistoryItem(StudentReportChatMessage message) {
        this.sessionId = message.getChatSession().getId();
        this.role = message.getRole().name();
        this.message = message.getContent();
        this.createdAt = message.getCreatedAt();
    }
}
