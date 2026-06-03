package io.github.uou_capstone.aiplatform.domain.course.notice.assistant.dto;

import io.github.uou_capstone.aiplatform.domain.course.notice.entity.NoticeCategory;
import io.github.uou_capstone.aiplatform.domain.course.notice.entity.NoticePriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class NoticeAssistantRequest {

    @NotBlank
    @Size(max = 500)
    private String topic;

    private NoticeCategory category;

    private NoticePriority priority;

    @Size(max = 10_000)
    private String previousDraft;
}
