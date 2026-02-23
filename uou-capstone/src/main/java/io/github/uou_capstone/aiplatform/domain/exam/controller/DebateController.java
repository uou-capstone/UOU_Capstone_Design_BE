package io.github.uou_capstone.aiplatform.domain.exam.controller;

import io.github.uou_capstone.aiplatform.domain.exam.dto.DebateRespondRequestDto;
import io.github.uou_capstone.aiplatform.domain.exam.dto.DebateRespondResponseDto;
import io.github.uou_capstone.aiplatform.domain.exam.dto.DebateStartRequestDto;
import io.github.uou_capstone.aiplatform.domain.exam.dto.DebateStartResponseDto;
import io.github.uou_capstone.aiplatform.domain.exam.service.DebateService;
import io.github.uou_capstone.aiplatform.domain.user.repository.UserRepository;
import io.github.uou_capstone.aiplatform.util.AuthorizationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 토론형 시험 상호작용 API Controller
 * 
 * API 엔드포인트:
 * - POST /api/exams/debate/start: Phase 1 - 토론 시작 (모드 설정 및 주제 선정)
 * - POST /api/exams/debate/respond: Phase 2 - 토론 응답 (사용자 입력 → AI 반박)
 */
@Tag(name = "토론형 시험 상호작용 API", description = "토론형 시험 실시간 상호작용 API")
@RestController
@RequestMapping("/api/exams/debate")
@RequiredArgsConstructor
public class DebateController {

    private final DebateService debateService;
    private final UserRepository userRepository;

    /**
     * Phase 1: 토론형 시험 시작
     * 
     * 엔드포인트: POST /api/exams/debate/start
     * 
     * 요청:
     * {
     *   "examSessionId": 1,
     *   "mode": "debate",  // 선택적: "debate", "socratic", "feynman"
     *   "topic": "사용자 지정 주제"  // 선택적
     * }
     * 
     * 응답:
     * {
     *   "examSessionId": 1,
     *   "phase": "PHASE1",
     *   "topic": "선정된 토론 주제",
     *   "settings": {...},
     *   "message": "토론 세션이 시작되었습니다."
     * }
     */
    @Operation(
            summary = "토론형 시험 시작",
            description = "Phase 1: 모드 설정 및 주제 선정을 통해 토론 세션을 시작합니다."
    )
    @PostMapping("/start")
    @PreAuthorize("hasAuthority('STUDENT') or hasAuthority('TEACHER')")
    public ResponseEntity<DebateStartResponseDto> startDebate(
            @Valid @RequestBody DebateStartRequestDto request,
            Authentication authentication) {
        Long userId = AuthorizationUtil.getCurrentUserId(userRepository);
        DebateStartResponseDto response = debateService.startDebate(request, userId);
        return ResponseEntity.ok(response);
    }

    /**
     * Phase 2: 토론 응답
     * 
     * 엔드포인트: POST /api/exams/debate/respond
     * 
     * 요청:
     * {
     *   "examSessionId": 1,
     *   "userInput": "사용자의 답변/입력"
     * }
     * 
     * 응답:
     * {
     *   "examSessionId": 1,
     *   "phase": "PHASE2",
     *   "debaterResponse": "AI 반박 내용",
     *   "score": 65,
     *   "evaluation": {...},
     *   "isCompleted": false,
     *   "message": "토론이 계속됩니다."
     * }
     */
    @Operation(
            summary = "토론 응답",
            description = "Phase 2: 사용자 입력에 대해 AI가 반박을 생성하고 평가합니다. 경쟁 루프를 통해 토론이 진행됩니다."
    )
    @PostMapping("/respond")
    @PreAuthorize("hasAuthority('STUDENT') or hasAuthority('TEACHER')")
    public ResponseEntity<DebateRespondResponseDto> respondToDebate(
            @Valid @RequestBody DebateRespondRequestDto request,
            Authentication authentication) {
        Long userId = AuthorizationUtil.getCurrentUserId(userRepository);
        DebateRespondResponseDto response = debateService.respondToDebate(request, userId);
        return ResponseEntity.ok(response);
    }
}
