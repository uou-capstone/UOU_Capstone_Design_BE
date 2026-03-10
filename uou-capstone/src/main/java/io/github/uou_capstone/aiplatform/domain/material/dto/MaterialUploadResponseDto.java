package io.github.uou_capstone.aiplatform.domain.material.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 강의 자료(PDF) 업로드 성공 시 응답 DTO.
 * 프론트에서 PDF 미리보기·채팅 UI용으로 url(또는 fileUrl)을 사용한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialUploadResponseDto {
    private Long materialId;
    private String displayName;
    private String materialType;
    /** 프론트에서 PDF 접근에 사용할 URL (예: /api/materials/123/file). fileUrl 별칭으로도 파싱 가능. */
    private String url;

    /** 프론트 호환: lectureApi.uploadMaterial 이 fileUrl 도 파싱하도록 되어 있을 수 있음 */
    public String getFileUrl() {
        return url;
    }
}
