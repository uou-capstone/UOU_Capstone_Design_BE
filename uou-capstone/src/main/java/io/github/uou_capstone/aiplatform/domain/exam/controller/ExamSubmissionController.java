package io.github.uou_capstone.aiplatform.domain.exam.controller;

import io.github.uou_capstone.aiplatform.domain.exam.dto.ExamSubmissionRequestDto;
import io.github.uou_capstone.aiplatform.domain.exam.dto.ExamSubmissionResponseDto;
import io.github.uou_capstone.aiplatform.domain.exam.service.ExamSubmissionService;
import io.github.uou_capstone.aiplatform.domain.exam.service.ExamGradingService;
import io.github.uou_capstone.aiplatform.domain.task.dto.AsyncTaskResponse;
import io.github.uou_capstone.aiplatform.service.AsyncTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 시험 응시 Controller
 * Version 2의 시험 응시 및 채점을 관리하는 REST API
 * 
 * API 엔드포인트:
 * - POST /api/exams/submission: 시험 응시 및 채점
 * - GET /api/exams/submission/{examResultId}: 시험 결과 조회
 */
@Tag(name = "시험 응시 API", description = "시험 응시 및 채점 결과 조회 API")
@RestController
@RequestMapping("/api/exams/submission")
@RequiredArgsConstructor
public class ExamSubmissionController {

    private final ExamSubmissionService examSubmissionService;
    private final ExamGradingService examGradingService;
    private final AsyncTaskService asyncTaskService;

    /**
     * 시험 응시 및 채점
     * 
     * 엔드포인트: POST /api/exams/submission
     * 
     * 요청 본문 (OX 문제):
     * {
     *   "examSessionId": 1,
     *   "answers": [
     *     {
     *       "questionId": 1,
     *       "answerText": null,
     *       "selectedOptionId": "O",
     *       "additionalData": null
     *     },
     *     {
     *       "questionId": 2,
     *       "answerText": null,
     *       "selectedOptionId": "X",
     *       "additionalData": null
     *     }
     *   ]
     * }
     * 
     * 요청 본문 (단답형/서술형):
     * {
     *   "examSessionId": 1,
     *   "answers": [
     *     {
     *       "questionId": 1,
     *       "answerText": "마르코프 체인은 이전 상태에만 의존하는 확률 과정입니다.",
     *       "selectedOptionId": null,
     *       "additionalData": null
     *     }
     *   ]
     * }
     * 
     * 응답:
     * {
     *   "examResultId": 1,
     *   "examSessionId": 1,
     *   "totalScore": 85.0,
     *   "maxScore": 100.0,
     *   "overallFeedback": "전반적으로 좋은 성적입니다. 다만 챕터 3 부분을 더 공부하시길 권장합니다.",
     *   "gradingDetails": {
     *     "examSessionId": 1,
     *     "totalScore": 85.0,
     *     "maxScore": 100.0,
     *     "questionGradings": [
     *       {
     *         "questionId": 1,
     *         "userAnswer": "O",
     *         "isCorrect": true,
     *         "score": 10.0,
     *         "feedback": "정답입니다! 마르코프 체인의 정의를 정확히 이해하셨습니다."
     *       },
     *       ...
     *     ],
     *     "overallFeedback": "..."
     *   }
     * }
     * 
     * 로직 흐름:
     * 1. Controller가 요청을 받아 Service에 전달
     * 2. Service가 권한 확인, 시험 세션 조회, 문제 조회, 답변 검증 수행
     * 3. Service가 ExamGraderAgent를 통해 답변 채점
     * 4. Service가 ExamResult 엔티티 생성 및 저장
     * 5. Service가 결과를 반환
     * 6. Controller가 HTTP 201 CREATED와 함께 응답 반환
     */
    @Operation(
            summary = "시험 응시 및 채점", 
            description = "시험에 답변을 제출하고 AI를 통해 자동 채점합니다. 시험 유형에 따라 답변 형식이 다릅니다."
    )
    @PostMapping
    @PreAuthorize("hasAnyAuthority('STUDENT', 'TEACHER')")
    public ResponseEntity<ExamSubmissionResponseDto> submitExam(
            @Valid @RequestBody ExamSubmissionRequestDto requestDto) {
        
        ExamSubmissionResponseDto response = examSubmissionService.submitExam(requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 시험 결과 조회
     * 
     * 엔드포인트: GET /api/exams/submission/{examResultId}
     * 
     * 응답:
     * {
     *   "examResultId": 1,
     *   "examSessionId": 1,
     *   "totalScore": 85.0,
     *   "maxScore": 100.0,
     *   "overallFeedback": "...",
     *   "gradingDetails": {...}
     * }
     * 
     * 로직 흐름:
     * 1. Controller가 examResultId를 받아 Service에 전달
     * 2. Service가 결과 조회, 권한 확인, 응답 구성 수행
     * 3. Service가 결과를 반환
     * 4. Controller가 HTTP 200 OK와 함께 응답 반환
     */
    @Operation(
            summary = "시험 결과 조회", 
            description = "이전에 응시한 시험의 결과를 조회합니다. 채점 상세 정보와 피드백을 확인할 수 있습니다."
    )
    @GetMapping("/{examResultId}")
    @PreAuthorize("hasAnyAuthority('STUDENT', 'TEACHER')")
    public ResponseEntity<ExamSubmissionResponseDto> getExamResult(@PathVariable Long examResultId) {
        
        ExamSubmissionResponseDto response = examSubmissionService.getExamResult(examResultId);
        return ResponseEntity.ok(response);
    }
}
