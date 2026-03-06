package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 챕터 정보
 */
@Getter
@Setter
public class ChapterDto {
    private Integer id;  // FastAPI 응답에 포함됨
    
    @JsonProperty("title")
    private String chapterTitle;
    
    @JsonProperty("objective")
    private String chapterDescription;
    
    @JsonProperty("estimated_sections")
    private Integer estimatedSections;
    
    @JsonProperty("key_topics")
    private List<String> keyTopics;
    
    @JsonProperty("must_include")
    private List<String> mustInclude;  // FastAPI 응답에 포함됨
    
    @JsonProperty("order")
    private Integer order;
}
