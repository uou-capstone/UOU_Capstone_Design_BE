package io.github.uou_capstone.aiplatform.domain.course.notice.dto;

import io.github.uou_capstone.aiplatform.domain.course.notice.entity.NoticeCategory;
import io.github.uou_capstone.aiplatform.domain.course.notice.entity.NoticePriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class NoticeCreateRequestDto {

    @NotBlank(message = "제목은 필수 입력값입니다.")
    @Size(max = 100, message = "제목은 100자 이하로 입력해주세요.")
    private String title;

    @NotBlank(message = "본문은 필수 입력값입니다.")
    @Size(max = 12000, message = "본문은 12000자 이하로 입력해주세요.")
    private String contentMarkdown;

    private NoticeCategory category;     // null → GENERAL
    private NoticePriority priority;     // null → NORMAL
    private Boolean pinned;              // null → false
}
