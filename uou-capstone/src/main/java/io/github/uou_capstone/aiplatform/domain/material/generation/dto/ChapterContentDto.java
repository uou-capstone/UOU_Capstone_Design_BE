package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * 챕터 콘텐츠 DTO
 * Phase 3 산출물: 각 챕터별 생성된 내용
 */
@Getter
@Setter
public class ChapterContentDto {
    private String chapterTitle;
    private Integer chapterOrder;
    private String content; // Markdown 형식의 본문
    private List<String> references; // 참고 자료 목록
    private Map<String, Object> metadata;
}
