package io.github.uou_capstone.aiplatform.domain.course.dto;

import io.github.uou_capstone.aiplatform.domain.material.entity.Material;
import lombok.Getter;

@Getter
public class MaterialSummaryDto {
    private final Long materialId;
    private final String displayName;
    private final String materialType;
    private final String filePath;
    private final String url;

    public MaterialSummaryDto(Material material) {
        this.materialId = material.getId();
        this.displayName = material.getDisplayName();
        this.materialType = material.getMaterialType();
        this.filePath = material.getFilePath();
        this.url = material.getUrl();
    }
}
