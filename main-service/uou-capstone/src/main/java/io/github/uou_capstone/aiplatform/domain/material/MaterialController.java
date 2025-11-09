package io.github.uou_capstone.aiplatform.domain.material;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Tag(name = "강의 자료 API", description = "강의 자료(PDF) 업로드 등 관련 API")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MaterialController {

    private final MaterialService materialService;

    @Operation(summary = "강의 자료(PDF) 업로드", description = "특정 강의에 PDF 파일을 업로드합니다.")
    @PostMapping(value = "/lectures/{lectureId}/materials", consumes = "multipart/form-data")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<String> uploadMaterial(
            @PathVariable Long lectureId,
            @RequestParam("file") MultipartFile file) { // "file"이라는 이름으로 파일을 받음

        try {
            materialService.uploadFile(lectureId, file);
            return ResponseEntity.status(HttpStatus.CREATED).body("파일이 성공적으로 업로드되었습니다.");
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("파일 업로드에 실패했습니다.");
        }
    }
}