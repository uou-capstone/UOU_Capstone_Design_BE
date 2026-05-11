package io.github.uou_capstone.aiplatform.domain.course.discussion.dto;

import io.github.uou_capstone.aiplatform.domain.course.discussion.entity.DiscussionCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class DiscussionCreateRequestDto {

    @NotBlank(message = "제목은 필수 입력값입니다.")
    @Size(max = 120, message = "제목은 120자 이하로 입력해주세요.")
    private String title;

    @NotBlank(message = "본문은 필수 입력값입니다.")
    @Size(max = 12000, message = "본문은 12000자 이하로 입력해주세요.")
    private String contentMarkdown;

    private DiscussionCategory category;     // null → FREE
    private Boolean pinned;                  // null → false
    private Boolean allowComments;           // null → true
}
