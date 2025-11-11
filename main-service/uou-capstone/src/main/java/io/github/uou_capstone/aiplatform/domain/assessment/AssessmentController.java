package io.github.uou_capstone.aiplatform.domain.assessment;

import io.github.uou_capstone.aiplatform.domain.assessment.dto.AssessmentCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.assessment.dto.AssessmentDetailDto;
import io.github.uou_capstone.aiplatform.domain.assessment.dto.AssessmentSimpleDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "평가 API", description = "평가(퀴즈/과제) 생성 등 관련 API")
@RestController
@RequestMapping("/api/assessments")
@RequiredArgsConstructor
public class AssessmentController {

    private final AssessmentService assessmentService;

    @Operation(summary = "평가 생성", description = "특정 과목에 문제 목록을 포함한 새로운 평가를 생성합니다.")
    @PostMapping("/courses/{courseId}")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<String> createAssessment(
            @PathVariable Long courseId,
            @RequestBody AssessmentCreateRequestDto requestDto) {

        assessmentService.createAssessment(courseId, requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body("평가가 성공적으로 생성되었습니다.");
    }

    @Operation(summary = "과목별 평가 목록 조회", description = "특정 과목에 속한 모든 평가의 목록을 조회합니다.")
    @GetMapping("/courses/{courseId}")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<List<AssessmentSimpleDto>> getAssessmentsForCourse(@PathVariable Long courseId) {
        List<AssessmentSimpleDto> assessments = assessmentService.getAssessmentsForCourse(courseId);
        return ResponseEntity.ok(assessments);
    }

    @Operation(summary = "평가 상세 조회", description = "특정 평가의 상세 정보(문제, 선택지 포함)를 조회합니다.")
    @GetMapping("/{assessmentId}")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<AssessmentDetailDto> getAssessmentDetail(@PathVariable Long assessmentId) {
        AssessmentDetailDto assessmentDetail = assessmentService.getAssessmentDetail(assessmentId);
        return ResponseEntity.ok(assessmentDetail);
    }

    @Operation(summary = "AI 퀴즈 생성 요청", description = "특정 강의 자료를 기반으로 AI 퀴즈 생성을 요청합니다.")
    // 예시: /api/assessments/ai-generate?courseId=1&lectureId=1
    @PostMapping("/ai-generate")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<String> generateAiQuiz(
            @RequestParam Long courseId,
            @RequestParam Long lectureId) {

        assessmentService.generateAiQuiz(courseId, lectureId);
        return ResponseEntity.ok("AI 퀴즈 생성 작업이 시작되었습니다.");
    }
}
