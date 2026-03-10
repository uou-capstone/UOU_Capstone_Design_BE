package io.github.uou_capstone.aiplatform.domain.material.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 강의별 자료 목록 항목 (GET /api/lectures/{lectureId}/materials 응답용)
 */
@Getter
@Builder
public class MaterialListItemDto {
    private Long materialId;
    private String displayName;
    private String materialType;
    private String url;
    private LocalDateTime createdAt;
}
