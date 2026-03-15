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

    /** 다운로드/미리보기용 URL. DB에 url이 없으면 서버 제공 파일 API 경로로 채움. */
    public MaterialSummaryDto(Material material) {
        this.materialId = material.getId();
        this.displayName = material.getDisplayName();
        this.materialType = material.getMaterialType();
        this.filePath = material.getFilePath();
        this.url = material.getUrl() != null && !material.getUrl().isBlank()
                ? material.getUrl()
                : "/api/materials/" + material.getId() + "/file";
    }
}
