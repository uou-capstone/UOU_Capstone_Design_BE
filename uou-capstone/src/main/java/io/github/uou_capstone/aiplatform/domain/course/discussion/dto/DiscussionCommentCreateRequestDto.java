package io.github.uou_capstone.aiplatform.domain.course.discussion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class DiscussionCommentCreateRequestDto {

    @NotBlank(message = "댓글 내용은 필수 입력값입니다.")
    @Size(max = 4000, message = "댓글은 4000자 이하로 입력해주세요.")
    private String contentMarkdown;

    /** 답글일 때만 채움. 같은 discussion 의 댓글 ID 여야 하며, 1단계까지만 허용. */
    private Long parentCommentId;
}
