package io.github.uou_capstone.aiplatform.domain.course.notice.dto;

import io.github.uou_capstone.aiplatform.domain.course.notice.entity.NoticeCategory;
import io.github.uou_capstone.aiplatform.domain.course.notice.entity.NoticePriority;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * PATCH 부분 갱신 — 모든 필드 nullable. Boolean 은 wrapper 사용 (null=그대로 유지).
 */
@Getter
@NoArgsConstructor
public class NoticeUpdateRequestDto {

    @Size(max = 100, message = "제목은 100자 이하로 입력해주세요.")
    private String title;

    @Size(max = 12000, message = "본문은 12000자 이하로 입력해주세요.")
    private String contentMarkdown;

    private NoticeCategory category;
    private NoticePriority priority;
    private Boolean pinned;
}
