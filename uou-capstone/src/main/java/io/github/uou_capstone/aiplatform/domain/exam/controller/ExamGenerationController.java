package io.github.uou_capstone.aiplatform.domain.exam.controller;

import io.github.uou_capstone.aiplatform.domain.exam.dto.ExamGenerationRequestDto;
import io.github.uou_capstone.aiplatform.domain.exam.dto.ExamGenerationResponseDto;
import io.github.uou_capstone.aiplatform.domain.exam.dto.ExamSessionListItemDto;
import io.github.uou_capstone.aiplatform.domain.exam.dto.ProfileConversationRequestDto;
import io.github.uou_capstone.aiplatform.domain.exam.dto.ProfileConversationResponseDto;
import io.github.uou_capstone.aiplatform.domain.exam.service.ExamGenerationService;
import io.github.uou_capstone.aiplatform.domain.task.dto.AsyncTaskResponse;
import io.github.uou_capstone.aiplatform.service.AsyncTaskService;
import io.github.uou_capstone.aiplatform.service.SessionRecoveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 시험 생성 Controller
 * Version 2의 5가지 시험 유형 생성을 관리하는 REST API
 * 
 * API 엔드포인트:
 * - POST /api/exams/generation/profile: 프로필 대화 1턴 (에이전트와 대화로 프로필 채우기)
 * - POST /api/exams/generation: 시험 생성
 * - GET /api/exams/generation/{examSessionId}: 시험 세션 조회
 * 
 * 지원하는 시험 유형:
 * - FLASH_CARD: 플래시카드
 * - OX_PROBLEM: OX 문제
 * - FIVE_CHOICE: 5지선다 문제
 * - SHORT_ANSWER: 단답형/서술형 문제
 * - DEBATE: 토론형 문제
 */
@Tag(name = "시험 생성 API", description = "AI 기반 시험 생성 API (5가지 시험 유형 지원)")
@RestController
@RequestMapping("/api/exams/generation")
@RequiredArgsConstructor
public class ExamGenerationController {

    private final ExamGenerationService examGenerationService;
    private final AsyncTaskService asyncTaskService;
    private final SessionRecoveryService sessionRecoveryService;

    /**
     * 시험 생성
     * 
     * 엔드포인트: POST /api/exams/generation
     * 
     * 요청 본문 (플래시카드):
     * {
     *   "lectureId": 1,
     *   "examType": "FLASH_CARD",
     *   "targetCount": 20,
     *   "lectureContent": "강의 자료 내용...",
     *   "userProfile": null  // 선택적, 없으면 AI가 자동 생성
     * }
     * 
     * 요청 본문 (OX 문제):
     * {
     *   "lectureId": 1,
     *   "examType": "OX_PROBLEM",
     *   "targetCount": 10,
     *   "lectureContent": "강의 자료 내용...",
     *   "userProfile": {
     *     "learningGoal": {...},
     *     "userStatus": {...},
     *     "interactionStyle": {...},
     *     "feedbackPreference": {...},
     *     "scopeBoundary": "LECTURE_MATERIAL_ONLY"
     *   }
     * }
     * 
     * 응답 (플래시카드):
     * {
     *   "examSessionId": 1,
     *   "examType": "FLASH_CARD",
     *   "flashCards": [
     *     {
     *       "categoryTag": "알고리즘",
     *       "frontContent": "마르코프 체인이란?",
     *       "backContent": "이전 상태에만 의존하는 확률 과정",
     *       "complexityLevel": "Intermediate"
     *     },
     *     ...
     *   ],
     *   "oxProblems": null,
     *   "fiveChoiceProblems": null,
     *   "shortAnswerProblems": null,
     *   "debateTopics": null,
     *   "usedProfile": {...},
     *   "totalCount": 20
     * }
     * 
     * 로직 흐름:
     * 1. Controller가 요청을 받아 Service에 전달
     * 2. Service가 권한 확인, 강의 조회, 강의 자료 조회, 세션 생성 수행
     * 3. Service가 ProfileAgent를 통해 TestProfile 생성/검증
     * 4. Service가 시험 유형별 GeneratorAgent 호출:
     *    - FLASH_CARD: FlashCardGeneratorAgent
     *    - OX_PROBLEM: OxProblemGeneratorAgent
     *    - FIVE_CHOICE: FiveChoiceGeneratorAgent
     *    - SHORT_ANSWER: ShortAnswerGeneratorAgent
     *    - DEBATE: DebateGeneratorAgent
     * 5. Service가 생성된 문제를 세션에 저장
     * 6. Service가 결과를 반환
     * 7. Controller가 ResponseEntity로 응답 반환
     */
    @Operation(
            summary = "시험 생성", 
            description = "시험 유형에 따라 문제를 생성합니다. 현재 FLASH_CARD와 OX_PROBLEM을 지원하며, 나머지 유형은 개발 중입니다."
    )
    @PostMapping
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<ExamGenerationResponseDto> generateExam(
            @Valid @RequestBody ExamGenerationRequestDto requestDto) {
        
        // Service 레이어에 요청 전달
        // Service에서 모든 비즈니스 로직 처리:
        // - 권한 확인
        // - 강의 정보 조회
        // - 강의 자료 조회
        // - 세션 생성
        // - Profile 생성/검증
        // - 시험 유형별 문제 생성
        // - 결과 저장
        ExamGenerationResponseDto response = examGenerationService.generateExam(requestDto);
        
        // HTTP 201 CREATED와 함께 응답 반환 (새로운 리소스 생성)
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 강의별 시험 세션 목록 조회
     *
     * 엔드포인트: GET /api/exams/generation/lectures/{lectureId}
     *
     * 강의 진입 시 호출해 로컬 상태를 서버 응답으로 채우면 새로고침/재로그인 후에도 목록이 복원됩니다.
     */
    @Operation(
            summary = "강의별 시험 세션 목록 조회",
            description = "해당 강의에 대한 시험 생성 세션 목록을 반환합니다. 강의 진입 시 호출해 목록을 복원할 수 있습니다."
    )
    @GetMapping("/lectures/{lectureId}")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<List<ExamSessionListItemDto>> listExamSessionsByLecture(@PathVariable Long lectureId) {
        List<ExamSessionListItemDto> list = examGenerationService.getExamSessionsByLectureId(lectureId);
        return ResponseEntity.ok(list);
    }

    /**
     * 시험 세션 조회
     * 
     * 엔드포인트: GET /api/exams/generation/{examSessionId}
     * 
     * 응답:
     * {
     *   "examSessionId": 1,
     *   "examType": "FLASH_CARD",
     *   "flashCards": [...],
     *   "oxProblems": null,
     *   "fiveChoiceProblems": null,
     *   "shortAnswerProblems": null,
     *   "debateTopics": null,
     *   "usedProfile": {...},
     *   "totalCount": 20
     * }
     * 
     * 로직 흐름:
     * 1. Controller가 examSessionId를 받아 Service에 전달
     * 2. Service가 세션 조회, 권한 확인, 응답 구성 수행
     * 3. Service가 세션 정보를 반환
     * 4. Controller가 ResponseEntity로 응답 반환
     */
    @Operation(
            summary = "시험 세션 조회", 
            description = "생성된 시험 세션을 조회합니다. 생성된 문제 리스트와 사용된 Profile을 확인할 수 있습니다."
    )
    @GetMapping("/{examSessionId}")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<ExamGenerationResponseDto> getExamSession(@PathVariable Long examSessionId) {
        
        // Service 레이어에 examSessionId 전달
        // Service에서 모든 비즈니스 로직 처리:
        // - 세션 조회
        // - 권한 확인
        // - 응답 구성
        ExamGenerationResponseDto response = examGenerationService.getExamSession(examSessionId);
        
        // HTTP 200 OK와 함께 응답 반환
        return ResponseEntity.ok(response);
    }

    /**
     * 프로필 대화 1턴 (문서: ai-service-endpoint-request.md §2, §4)
     *
     * 엔드포인트: POST /api/exams/generation/profile
     *
     * 사용자가 에이전트와 대화로 프로필을 채울 때, 턴마다 호출.
     * - 첫 턴: lectureContent, examType(선택), userMessage는 빈 문자열 또는 생략
     * - 2턴부터: 이전 응답의 updatedProfile을 existingProfile로, 사용자 입력을 userMessage로 전달
     * - status가 "COMPLETE"가 되면 updatedProfile을 시험 생성 요청(POST /api/exams/generation)의 userProfile로 전달
     *
     * 요청 예시 (첫 턴):
     * {
     *   "lectureContent": "강의 내용 또는 본문 텍스트",
     *   "examType": "FLASH_CARD",
     *   "userMessage": ""
     * }
     *
     * 요청 예시 (2턴 이후, existingProfile 포함):
     * {
     *   "lectureContent": "string",
     *   "examType": "string",
     *   "existingProfile": {
     *     "learningGoal": { "focusAreas": ["string"], "targetDepth": "string", "questionModality": "string" },
     *     "userStatus": { "proficiencyLevel": "string", "weaknessFocus": true },
     *     "interactionStyle": { "languagePreference": "string", "scenarioBased": true },
     *     "feedbackPreference": { "strictness": "string", "explanationDepth": "string" },
     *     "scopeBoundary": "LECTURE_MATERIAL_ONLY"
     *   },
     *   "userMessage": "string"
     * }
     *
     * 응답 예시 (INCOMPLETE):
     * { "status": "INCOMPLETE", "agentMessage": "어떤 주제 위주로...?", "missingInfo": ["learning_goal"], "updatedProfile": {...} }
     *
     * 응답 예시 (COMPLETE):
     * { "status": "COMPLETE", "agentMessage": "프로필이 확정되었습니다.", "missingInfo": [], "updatedProfile": {...} }
     */
    @Operation(
            summary = "프로필 대화 1턴",
            description = "에이전트와 대화로 시험 프로필을 채웁니다. status가 COMPLETE가 될 때까지 반복 호출한 뒤, updatedProfile을 시험 생성 요청의 userProfile로 넣어주세요."
    )
    @PostMapping("/profile")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<ProfileConversationResponseDto> chatProfileTurn(
            @Valid @RequestBody ProfileConversationRequestDto request) {
        ProfileConversationResponseDto response = examGenerationService.chatProfileTurn(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 시험 생성 비동기 처리 시작
     * 
     * 엔드포인트: POST /api/exams/generation/async
     * 
     * 요청 본문:
     * {
     *   "lectureId": 1,
     *   "examType": "FLASH_CARD",
     *   "targetCount": 20,
     *   "lectureContent": "강의 자료 내용...",
     *   "userProfile": null
     * }
     * 
     * 응답:
     * {
     *   "taskId": "uuid-1234-5678",
     *   "status": "accepted",
     *   "message": "시험 생성이 시작되었습니다.",
     *   "statusUrl": "/api/tasks/uuid-1234-5678/status"
     * }
     */
    @Operation(
            summary = "시험 생성 비동기 처리 시작", 
            description = "시험 생성을 비동기로 처리합니다. 즉시 taskId를 반환하며, 진행 상황은 /api/tasks/{taskId}/status에서 확인할 수 있습니다."
    )
    @PostMapping("/async")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<AsyncTaskResponse> startAsyncExamGeneration(
            @Valid @RequestBody ExamGenerationRequestDto requestDto) {
        
        // ========== 1단계: taskId 생성 ==========
        String taskId = java.util.UUID.randomUUID().toString();
        
        // ========== 2단계: 작업 등록 ==========
        asyncTaskService.createTask(taskId, "시험 생성 대기 중...");
        
        // ========== 3단계: 비동기 처리 시작 ==========
        examGenerationService.generateExamAsync(taskId, requestDto);
        
        // ========== 4단계: 즉시 응답 반환 ==========
        AsyncTaskResponse response = AsyncTaskResponse.builder()
                .taskId(taskId)
                .status("accepted")
                .message("시험 생성이 시작되었습니다.")
                .statusUrl("/api/tasks/" + taskId + "/status")
                .build();
        
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * 시험 세션 복구
     * 
     * 엔드포인트: POST /api/exams/generation/{examSessionId}/recover
     * 
     * 설명:
     * - 실패한 ExamSession의 상태를 FAILED에서 GENERATING으로 변경하여 재시도 가능한 상태로 복구합니다.
     * 
     * 응답:
     * {
     *   "message": "시험 세션이 복구되었습니다."
     * }
     */
    @Operation(
            summary = "시험 세션 복구", 
            description = "실패한 ExamSession을 복구하여 재시도 가능한 상태로 만듭니다."
    )
    @PostMapping("/{examSessionId}/recover")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Map<String, String>> recoverExamSession(@PathVariable Long examSessionId) {
        sessionRecoveryService.recoverExamSession(examSessionId);
        
        Map<String, String> response = new HashMap<>();
        response.put("message", "시험 세션이 복구되었습니다. 다시 시도할 수 있습니다.");
        return ResponseEntity.ok(response);
    }
}
