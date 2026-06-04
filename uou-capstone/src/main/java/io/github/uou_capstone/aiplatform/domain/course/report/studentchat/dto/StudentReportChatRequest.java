package io.github.uou_capstone.aiplatform.domain.course.report.studentchat.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * Student Report Chatbot 요청.
 *
 * <p>FastAPI {@code POST /bridge/report/student_chat_stream} 호출 시 Spring 측이 보강하는 필드:
 * {@code context} ({@link io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.StudentAiReportContextResponse}),
 * {@code report} ({@link io.github.uou_capstone.aiplatform.domain.course.report.dto.StudentReportDetailResponse}).
 */
@Getter
@Setter
@NoArgsConstructor
public class StudentReportChatRequest {

    /** 단발 사용자 메시지. {@code messages}와 동시에 사용 시 FastAPI 측이 messages 우선. */
    @Size(max = 4000)
    private String question;

    /** {role, content} 형태 메시지 배열. {@code question} 단일 사용 시 비워둘 수 있음. */
    @Size(max = 30)
    private List<Map<String, Object>> messages;

    /** 모델 명시 (선택 — null이면 FastAPI 기본 모델). */
    private String model;

    private Long sessionId;

    @JsonIgnore
    @AssertTrue(message = "question 또는 messages 중 하나는 필수입니다.")
    public boolean isQuestionOrMessagesPresent() {
        boolean hasQuestion = question != null && !question.isBlank();
        boolean hasMessages = messages != null && !messages.isEmpty();
        return hasQuestion || hasMessages;
    }
}
