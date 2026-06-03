package io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * Report Criteria Assistant Chat request.
 *
 * <p>Spring fills {@code courseId}, {@code courseName}, {@code builtInCriteria[]},
 * and {@code additionalCriteria[]} before forwarding to FastAPI.
 */
@Getter
@Setter
@NoArgsConstructor
public class CriteriaAssistantChatRequest {

    @Size(max = 4000)
    private String message;

    @Size(max = 30)
    private List<Map<String, Object>> messages;

    @Size(max = 50)
    private List<Map<String, Object>> history;

    private Map<String, Object> currentProposal;

    private String model;

    private Map<String, Object> responseJsonSchema;

    @JsonIgnore
    @AssertTrue(message = "message 또는 messages 중 하나는 필수입니다.")
    public boolean isMessageOrMessagesPresent() {
        boolean hasMessage = message != null && !message.isBlank();
        boolean hasMessages = messages != null && !messages.isEmpty();
        return hasMessage || hasMessages;
    }
}
