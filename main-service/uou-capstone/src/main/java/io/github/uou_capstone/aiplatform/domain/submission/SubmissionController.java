package io.github.uou_capstone.aiplatform.domain.submission;

import io.github.uou_capstone.aiplatform.domain.submission.Dto.SubmissionRequestDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "답안 제출 API", description = "학생의 평가 답안 제출 관련 API")
@RestController
@RequestMapping("/api/assessments/{assessmentId}/submissions")
@RequiredArgsConstructor
public class SubmissionController {

    private final SubmissionService submissionService;

    @Operation(summary = "답안 제출", description = "학생이 특정 평가에 대한 답안을 제출합니다.")
    @PostMapping
    @PreAuthorize("hasAuthority('STUDENT')")
    public ResponseEntity<String> createSubmission(
            @PathVariable Long assessmentId,
            @RequestBody SubmissionRequestDto requestDto) {

        submissionService.createSubmission(assessmentId, requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body("답안이 성공적으로 제출되었습니다.");
    }
}