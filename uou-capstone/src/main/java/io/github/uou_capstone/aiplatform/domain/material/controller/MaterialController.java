package io.github.uou_capstone.aiplatform.domain.material.controller;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.material.dto.MaterialListItemDto;
import io.github.uou_capstone.aiplatform.domain.material.dto.MaterialUploadResponseDto;
import io.github.uou_capstone.aiplatform.domain.material.entity.Material;
import io.github.uou_capstone.aiplatform.domain.material.service.MaterialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.io.IOException;

@Tag(name = "강의 자료 API", description = "강의 자료(PDF) 업로드 등 관련 API")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MaterialController {

    private final MaterialService materialService;

    private static final String MATERIAL_FILE_PATH_PREFIX = "/api/materials/";
    private static final String MATERIAL_FILE_PATH_SUFFIX = "/file";

    @Operation(summary = "강의별 자료 목록 조회", description = "해당 강의에 업로드된 자료 목록을 반환합니다. 강의 진입 시 호출해 로컬 상태를 채우면 새로고침/재로그인 후에도 목록이 복원됩니다.")
    @GetMapping("/lectures/{lectureId}/materials")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<List<MaterialListItemDto>> listMaterialsByLecture(@PathVariable Long lectureId) {
        List<MaterialListItemDto> list = materialService.getMaterialsByLectureId(lectureId);
        return ResponseEntity.ok(list);
    }

    @Operation(summary = "강의 자료(PDF) 업로드", description = "특정 강의에 PDF 파일을 업로드합니다. 응답에 materialId, displayName, materialType, url을 포함하여 프론트에서 PDF 미리보기·채팅에 사용할 수 있습니다.")
    @PostMapping(value = "/lectures/{lectureId}/materials", consumes = "multipart/form-data", produces = "application/json")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<MaterialUploadResponseDto> uploadMaterial(
            @PathVariable Long lectureId,
            @RequestParam("file") MultipartFile file) {

        try {
            Material material = materialService.uploadFile(lectureId, file);
            String url = MATERIAL_FILE_PATH_PREFIX + material.getId() + MATERIAL_FILE_PATH_SUFFIX;
            MaterialUploadResponseDto body = MaterialUploadResponseDto.builder()
                    .materialId(material.getId())
                    .displayName(material.getDisplayName())
                    .materialType(material.getMaterialType() != null ? material.getMaterialType() : "FILE")
                    .url(url)
                    .build();
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        } catch (IOException e) {
            throw new BusinessException(CommonErrorCode.FILE_UPLOAD_FAILED, "파일 업로드 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    @Operation(summary = "강의 자료(PDF) 다운로드·미리보기", description = "업로드 시 응답의 url로 요청 시 PDF 바이트를 반환합니다. 해당 강의의 선생님 또는 수강생만 접근 가능합니다.")
    @GetMapping(value = "/materials/{materialId}/file", produces = "application/pdf")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<byte[]> getMaterialFile(@PathVariable Long materialId) {
        byte[] body = materialService.getFileBytes(materialId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentLength(body.length);
        return ResponseEntity.ok().headers(headers).body(body);
    }

    @Operation(summary = "강의 자료 삭제", description = "특정 강의 자료를 삭제합니다.")
    @DeleteMapping("/materials/{materialId}")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Void> deleteMaterial(@PathVariable Long materialId) {
        materialService.deleteMaterial(materialId);
        return ResponseEntity.noContent().build();
    }
}
