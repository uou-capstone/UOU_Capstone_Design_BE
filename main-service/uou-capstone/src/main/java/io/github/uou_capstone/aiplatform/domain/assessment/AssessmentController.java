package io.github.uou_capstone.aiplatform.domain.assessment;

import io.github.uou_capstone.aiplatform.domain.assessment.dto.AssessmentCreateRequestDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "평가 API", description = "평가(퀴즈/과제) 생성 등 관련 API")
@RestController
@RequestMapping("/api/courses/{courseId}/assessments")
@RequiredArgsConstructor
public class AssessmentController {

    private final AssessmentService assessmentService;

    @Operation(summary = "평가 생성", description = "특정 과목에 문제 목록을 포함한 새로운 평가를 생성합니다.")
    @PostMapping
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<String> createAssessment(
            @PathVariable Long courseId,
            @RequestBody AssessmentCreateRequestDto requestDto) {

        assessmentService.createAssessment(courseId, requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body("평가가 성공적으로 생성되었습니다.");
    }
}