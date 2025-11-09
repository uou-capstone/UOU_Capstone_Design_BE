package io.github.uou_capstone.aiplatform.domain.course.lecture.dto;

import io.github.uou_capstone.aiplatform.domain.course.lecture.ContentType;
import io.github.uou_capstone.aiplatform.domain.course.lecture.GeneratedContent;
import lombok.Getter;

@Getter
public class GeneratedContentResponseDto {
    private final Long contentId;
    private final ContentType contentType;
    private final String contentData; // 실제 텍스트 내용

    public GeneratedContentResponseDto(GeneratedContent content) {
        this.contentId = content.getId();
        this.contentType = content.getContentType();
        this.contentData = content.getContentData();
    }
}