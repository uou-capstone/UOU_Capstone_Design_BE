package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 챕터 정보
 */
@Getter
@Setter
public class ChapterDto {
    private String chapterTitle;
    private String chapterDescription;
    private Integer estimatedSections;
    private List<String> keyTopics;
    private Integer order;
}
