package io.github.uou_capstone.aiplatform.domain.material.controller;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.material.service.MaterialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Tag(name = "강의 자료 API", description = "강의 자료(PDF) 업로드 등 관련 API")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MaterialController {

    private final MaterialService materialService;

    @Operation(summary = "강의 자료(PDF) 업로드", description = "특정 강의에 PDF 파일을 업로드합니다. 응답은 JSON이며, message만 포함합니다. 이 값을 URL로 사용하지 마세요.")
    @PostMapping(value = "/lectures/{lectureId}/materials", consumes = "multipart/form-data", produces = "application/json")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Map<String, String>> uploadMaterial(
            @PathVariable Long lectureId,
            @RequestParam("file") MultipartFile file) {

        try {
            materialService.uploadFile(lectureId, file);
            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(Map.of("message", "파일이 성공적으로 업로드되었습니다."));
        } catch (IOException e) {
            throw new BusinessException(CommonErrorCode.FILE_UPLOAD_FAILED, "파일 업로드 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    @Operation(summary = "강의 자료 삭제", description = "특정 강의 자료를 삭제합니다.")
    @DeleteMapping("/materials/{materialId}")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Void> deleteMaterial(@PathVariable Long materialId) {
        materialService.deleteMaterial(materialId);
        return ResponseEntity.noContent().build();
    }
}
