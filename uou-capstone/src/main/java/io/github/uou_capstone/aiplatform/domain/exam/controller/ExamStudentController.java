package io.github.uou_capstone.aiplatform.domain.exam.controller;

import io.github.uou_capstone.aiplatform.domain.exam.dto.student.StudentExamDetailDto;
import io.github.uou_capstone.aiplatform.domain.exam.service.ExamGenerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 학생용 시험 상세 조회 API.
 *
 * <p>역할 분리: 교사용 GET /api/exams/generation/{id} 는 정답 포함 응답을 유지하고,
 * 학생용은 별도 경로에서 정답 제거된 응답을 내려준다.
 *
 * <p>권한: STUDENT 전용 + 해당 강의실 Course 의 ACTIVE 수강이어야 함
 * (서비스 레이어에서 {@code CourseAccessService.loadCourseAsParticipant} 로 검증).
 */
@Tag(name = "학생용 시험 API", description = "학생이 응시 전에 시험 문제(정답 제외)를 조회하는 API")
@RestController
@RequestMapping("/api/exams/student")
@RequiredArgsConstructor
public class ExamStudentController {

    private final ExamGenerationService examGenerationService;

    @Operation(
            summary = "[학생] 시험 상세 조회",
            description = "응시 전 학생이 시험 문제를 조회한다. 정답·해설·평가 기준은 응답에서 제외된다."
    )
    @GetMapping("/{examSessionId}")
    @PreAuthorize("hasAuthority('STUDENT')")
    public ResponseEntity<StudentExamDetailDto> getExamForStudent(@PathVariable Long examSessionId) {
        return ResponseEntity.ok(examGenerationService.getStudentExamSession(examSessionId));
    }
}
