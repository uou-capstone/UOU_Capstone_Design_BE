package io.github.uou_capstone.aiplatform.domain.material.generation.controller;

import io.github.uou_capstone.aiplatform.domain.material.generation.dto.*;
        import io.github.uou_capstone.aiplatform.domain.material.generation.listener.MaterialGenerationProgressListener;
import io.github.uou_capstone.aiplatform.domain.material.generation.service.MaterialGenerationService;
import io.github.uou_capstone.aiplatform.domain.task.dto.AsyncTaskResponse;
import io.github.uou_capstone.aiplatform.service.AsyncTaskService;
import io.github.uou_capstone.aiplatform.service.DistributedLockService;
import io.github.uou_capstone.aiplatform.service.SessionRecoveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

/**
 * 강의 자료 생성 Controller
 * Version 2의 5단계 파이프라인을 관리하는 REST API
 * 
 * API 엔드포인트:
 * - POST /api/materials/generation/phase1: Phase 1 시작 (DraftPlan 생성)
 * - POST /api/materials/generation/phase2: Phase 2 처리 (사용자 피드백)
 * - GET /api/materials/generation/{sessionId}/status: 생성 상태 조회
 */
@Slf4j
@Tag(name = "강의 자료 생성 API", description = "AI 기반 강의 자료 생성 5단계 파이프라인 API")
@RestController
@RequestMapping("/api/materials/generation")
@RequiredArgsConstructor
public class MaterialGenerationController {

    private final MaterialGenerationService materialGenerationService;
    private final AsyncTaskService asyncTaskService;
    private final SessionRecoveryService sessionRecoveryService;
    private final DistributedLockService distributedLockService;
    private final MaterialGenerationProgressListener progressListener;

    /**
     * Phase 1: 초기 키워드 기반 DraftPlan 생성
     * 
     * 엔드포인트: POST /api/materials/generation/phase1
     * 
     * 요청 본문:
     * {
     *   "lectureId": 1,
     *   "keyword": "마르코프 체인"
     * }
     * 
     * 응답:
     * {
     *   "sessionId": 1,
     *   "draftPlan": {
     *     "projectMeta": {...},
     *     "styleGuide": {...},
     *     "chapters": [...]
     *   },
     *   "progressPercentage": 20,
     *   "message": "Phase 1 완료: 기획안 초안이 생성되었습니다."
     * }
     * 
     * 로직 흐름:
     * 1. Controller가 요청을 받아 Service에 전달
     * 2. Service가 권한 확인, 강의 조회, 세션 생성, Agent 호출 수행
     * 3. Service가 생성된 DraftPlan과 sessionId를 반환
     * 4. Controller가 ResponseEntity로 응답 반환
     */
    @Operation(
            summary = "Phase 1: 기획안 초안 생성", 
            description = "사용자가 입력한 키워드를 기반으로 강의 자료 기획안 초안(DraftPlan)을 생성합니다."
    )
    @PostMapping("/phase1")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<MaterialGenerationPhase1ResponseDto> startPhase1(
            @Valid @RequestBody MaterialGenerationPhase1RequestDto requestDto) {
        
        // Service 레이어에 요청 전달
        // Service에서 모든 비즈니스 로직 처리:
        // - 권한 확인
        // - 강의 정보 조회
        // - 세션 생성
        // - PlanningAgent 호출
        // - 결과 저장
        MaterialGenerationPhase1ResponseDto response = materialGenerationService.startPhase1(requestDto);
        
        // HTTP 200 OK와 함께 응답 반환
        return ResponseEntity.ok(response);
    }

    /**
     * Phase 2: 사용자 피드백 기반 FinalizedBrief 생성
     * 
     * 엔드포인트: POST /api/materials/generation/phase2
     * 
     * 요청 본문 (확정):
     * {
     *   "sessionId": 1,
     *   "action": "confirm"
     * }
     * 
     * 요청 본문 (수정 요청):
     * {
     *   "sessionId": 1,
     *   "action": "feedback",
     *   "feedback": "챕터 3의 내용을 더 자세히 설명해주세요"
     * }
     * 
     * 응답 (확정):
     * {
     *   "sessionId": 1,
     *   "finalizedBrief": {
     *     "projectMeta": {...},
     *     "styleGuide": {...},
     *     "chapters": [...]
     *   },
     *   "progressPercentage": 40,
     *   "message": "Phase 2 완료: 기획안이 확정되었습니다."
     * }
     * 
     * 로직 흐름:
     * 1. Controller가 요청을 받아 Service에 전달
     * 2. Service가 세션 조회, 권한 확인, Phase 확인, DraftPlan 조회 수행
     * 3. action에 따라:
     *    - "confirm": DraftPlan을 FinalizedBrief로 변환
     *    - "feedback": UpdateAgent를 통해 DraftPlan 수정
     * 4. Service가 결과를 반환
     * 5. Controller가 ResponseEntity로 응답 반환
     */
    @Operation(
            summary = "Phase 2: 기획안 확정 또는 수정", 
            description = "사용자 피드백을 기반으로 기획안을 확정하거나 수정합니다. action='confirm'은 확정, action='feedback'은 수정 요청입니다."
    )
    @PostMapping("/phase2")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<MaterialGenerationPhase2ResponseDto> processPhase2(
            @Valid @RequestBody MaterialGenerationPhase2RequestDto requestDto) {
        
        // Service 레이어에 요청 전달
        // Service에서 모든 비즈니스 로직 처리:
        // - 세션 조회
        // - 권한 확인
        // - Phase 확인
        // - DraftPlan 조회
        // - 사용자 피드백 처리 (확정 또는 수정)
        // - 결과 저장
        MaterialGenerationPhase2ResponseDto response = materialGenerationService.processPhase2(requestDto);
        
        // HTTP 200 OK와 함께 응답 반환
        return ResponseEntity.ok(response);
    }

    /**
     * 생성 상태 조회
     * 
     * 엔드포인트: GET /api/materials/generation/{sessionId}/status
     * 
     * 응답:
     * {
     *   "sessionId": 1,
     *   "currentPhase": "PHASE2",
     *   "progressPercentage": 40,
     *   "finalDocument": null,
     *   "errorMessage": null
     * }
     * 
     * 로직 흐름:
     * 1. Controller가 sessionId를 받아 Service에 전달
     * 2. Service가 세션 조회, 권한 확인, 상태 정보 구성 수행
     * 3. Service가 상태 정보를 반환
     * 4. Controller가 ResponseEntity로 응답 반환
     */
    @Operation(
            summary = "생성 상태 조회", 
            description = "강의 자료 생성 진행 상태를 조회합니다. currentPhase, progressPercentage, errorMessage 등을 확인할 수 있습니다."
    )
    @GetMapping("/{sessionId}/status")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<MaterialGenerationStatusDto> getStatus(@PathVariable Long sessionId) {
        
        // Service 레이어에 sessionId 전달
        // Service에서 모든 비즈니스 로직 처리:
        // - 세션 조회
        // - 권한 확인
        // - 상태 정보 구성
        MaterialGenerationStatusDto response = materialGenerationService.getStatus(sessionId);
        
        // HTTP 200 OK와 함께 응답 반환
        return ResponseEntity.ok(response);
    }

    /**
     * [조회 전용] 세션 재개용 — 강의 기준 최근 세션 조회
     *
     * 사용 시점: 창을 닫았다가 다시 열었을 때, 또는 새로고침 후 sessionId를 모를 때.
     * 입력: lectureId만 필요 (sessionId 불필요).
     * 동작: 해당 강의 + 현재 로그인한 교사가 만든 가장 최근 세션을 조회하여
     *       sessionId, currentPhase, draftPlan, finalizedBrief 등 재개에 필요한 데이터만 반환 (DB 수정 없음).
     * recover와의 차이: recover는 "sessionId를 알고 있을 때, 그 세션의 에러를 지워 재시도"용.
     */
    @Operation(
            summary = "[조회] 세션 재개 — 최근 세션 조회",
            description = "창 닫힘/새로고침 후 sessionId를 모를 때 사용. lectureId만으로 해당 강의의 가장 최근 생성 세션(sessionId, 단계, 기획안 등)을 조회합니다. DB는 수정하지 않습니다. (에러 해제·재시도는 recover API 사용)"
    )
    @GetMapping("/lectures/{lectureId}/latest-session")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<MaterialGenerationResumeDto> getLatestSession(@PathVariable Long lectureId) {
        MaterialGenerationResumeDto response = materialGenerationService.findLatestGenerationSessionByLectureId(lectureId);
        return ResponseEntity.ok(response);
    }

    /**
     * Phase 3: 콘텐츠 생성 (챕터 분해 및 본문 작성)
     * 
     * 엔드포인트: POST /api/materials/generation/phase3
     * 
     * 요청 본문:
     * {
     *   "sessionId": 1
     * }
     * 
     * 응답:
     * {
     *   "sessionId": 1,
     *   "chapterContentList": {
     *     "chapters": [
     *       {
     *         "chapterTitle": "마르코프 체인의 기본",
     *         "content": "# 마르코프 체인의 기본\n\n...",
     *         "summary": "...",
     *         "keywords": "..."
     *       }
     *     ]
     *   },
     *   "progressPercentage": 60,
     *   "message": "Phase 3 완료: 콘텐츠 생성이 완료되었습니다."
     * }
     */
    @Operation(
            summary = "Phase 3: 콘텐츠 생성", 
            description = "챕터를 하위 주제로 분해하고 Markdown 본문을 작성합니다."
    )
    @PostMapping("/phase3")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<MaterialGenerationPhase3ResponseDto> processPhase3(
            @Valid @RequestBody MaterialGenerationPhase3RequestDto requestDto) {
        
        MaterialGenerationPhase3ResponseDto response = materialGenerationService.processPhase3(requestDto);
        return ResponseEntity.ok(response);
    }

    /**
     * Phase 4: 검증 및 수정
     * 
     * 엔드포인트: POST /api/materials/generation/phase4
     * 
     * 요청 본문:
     * {
     *   "sessionId": 1
     * }
     * 
     * 응답:
     * {
     *   "sessionId": 1,
     *   "verifiedContent": {
     *     "chapters": [...],
     *     "qualityChecks": ["정확성", "완전성", "일관성"],
     *     "verificationMetadata": {...}
     *   },
     *   "progressPercentage": 80,
     *   "message": "Phase 4 완료: 콘텐츠 검증 및 수정이 완료되었습니다."
     * }
     */
    @Operation(
            summary = "Phase 4: 검증 및 수정", 
            description = "콘텐츠의 검색 결과 충분성을 검증하고 품질을 검증합니다."
    )
    @PostMapping("/phase4")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<MaterialGenerationPhase4ResponseDto> processPhase4(
            @Valid @RequestBody MaterialGenerationPhase4RequestDto requestDto) {
        
        MaterialGenerationPhase4ResponseDto response = materialGenerationService.processPhase4(requestDto);
        return ResponseEntity.ok(response);
    }

    /**
     * Phase 5: 최종 조립
     * 
     * 엔드포인트: POST /api/materials/generation/phase5
     * 
     * 요청 본문:
     * {
     *   "sessionId": 1
     * }
     * 
     * 응답:
     * {
     *   "sessionId": 1,
     *   "finalDocument": "# 강의 자료\n\n...",
     *   "documentUrl": "/api/materials/generation/1/document",
     *   "progressPercentage": 100,
     *   "message": "Phase 5 완료: 최종 문서가 생성되었습니다."
     * }
     */
    @Operation(
            summary = "Phase 5: 최종 조립", 
            description = "검증된 콘텐츠를 최종 Markdown 문서로 조립합니다."
    )
    @PostMapping("/phase5")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<MaterialGenerationPhase5ResponseDto> processPhase5(
            @Valid @RequestBody MaterialGenerationPhase5RequestDto requestDto) {
        
        MaterialGenerationPhase5ResponseDto response = materialGenerationService.processPhase5(requestDto);
        return ResponseEntity.ok(response);
    }

    /**
     * 최종 문서 다운로드
     * 
     * 엔드포인트: GET /api/materials/generation/{sessionId}/document
     * 
     * 응답: Markdown 문서 (text/markdown)
     * 
     * 로직 흐름:
     * 1. Controller가 sessionId를 받아 Service에 전달
     * 2. Service가 세션 조회, 권한 확인, 최종 문서 조회 수행
     * 3. Service가 Markdown 문서를 반환
     * 4. Controller가 HTTP 200 OK와 함께 Markdown 문서를 반환
     */
    @Operation(
            summary = "최종 문서 다운로드", 
            description = "Phase 5에서 생성된 최종 Markdown 문서를 다운로드합니다."
    )
    @GetMapping("/{sessionId}/document")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<String> downloadDocument(@PathVariable Long sessionId) {
        
        String document = materialGenerationService.getFinalDocument(sessionId);
        
        return ResponseEntity.ok()
                .header("Content-Type", "text/markdown; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"lecture-material-" + sessionId + ".md\"")
                .body(document);
    }

    /**
     * Phase 3-5 비동기 처리 시작
     * 
     * 엔드포인트: POST /api/materials/generation/async
     * 
     * 요청 본문:
     * {
     *   "sessionId": 1
     * }
     * 
     * 응답:
     * {
     *   "taskId": "uuid-1234-5678",
     *   "status": "accepted",
     *   "message": "강의 자료 생성이 시작되었습니다.",
     *   "statusUrl": "/api/tasks/uuid-1234-5678/status"
     * }
     * 
     * 로직 흐름:
     * 1. Controller가 요청을 받아 taskId 생성
     * 2. AsyncTaskService에 작업 등록
     * 3. MaterialGenerationService의 비동기 메서드 호출
     * 4. 즉시 taskId와 statusUrl 반환
     */
    @Operation(
            summary = "Phase 3-5 비동기 처리 시작", 
            description = "Phase 3-5를 비동기로 처리합니다. 즉시 taskId를 반환하며, 진행 상황은 /api/tasks/{taskId}/status에서 확인할 수 있습니다."
    )
    @PostMapping("/async")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<AsyncTaskResponse> startAsyncGeneration(
            @Valid @RequestBody MaterialGenerationAsyncRequestDto requestDto) {
        
        // ✅ 분산 락 적용: sessionId 기반으로 중복 실행 방지
        // - 락 키: "phase3-5:{sessionId}"
        // - 대기 시간: 5초 (이미 진행 중이면 즉시 실패)
        // - 락 유지 시간: 1200초 (20분, Phase 3-5 최대 소요 시간)
        String lockKey = "phase3-5:" + requestDto.getSessionId();
        
        return distributedLockService.executeWithLock(lockKey, 5, 1200, () -> {
            // ========== 1단계: taskId 생성 ==========
            String taskId = java.util.UUID.randomUUID().toString();
            
            // ========== 2단계: 작업 등록 ==========
            asyncTaskService.createTask(taskId, "강의 자료 생성 대기 중...");
            
            // ========== 3단계: 비동기 처리 시작 ==========
            materialGenerationService.processPhase3To5Async(taskId, requestDto.getSessionId());
            
            // ========== 4단계: 즉시 응답 반환 ==========
            AsyncTaskResponse response = AsyncTaskResponse.builder()
                    .taskId(taskId)
                    .status("accepted")
                    .message("강의 자료 생성이 시작되었습니다.")
                    .statusUrl("/api/tasks/" + taskId + "/status")
                    .build();
            
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        });
    }

    /**
     * [상태 수정] 세션 복구 — 에러 해제 후 재시도 가능하게
     *
     * 사용 시점: Phase 3~5 실행 중 에러가 났을 때, 사용자가 "다시 시도" 버튼을 누를 때.
     * 입력: sessionId 필요 (화면에 이미 세션 정보가 있을 때).
     * 동작: 해당 세션의 errorMessage를 제거하여 재시도 가능 상태로 만듦 (Phase/진행률은 유지).
     * latest-session과의 차이: latest-session은 sessionId를 모를 때 lectureId로 최근 세션을 "조회"만 하는 API.
     *
     * 엔드포인트: POST /api/materials/generation/{sessionId}/recover
     * 응답: { "message": "세션이 복구되었습니다. 다시 시도할 수 있습니다." }
     */
    @Operation(
            summary = "[수정] 세션 복구 — 에러 해제 후 재시도",
            description = "이미 sessionId를 알고 있을 때 사용. 실패한 세션의 에러 메시지를 제거해 재시도 가능 상태로 만듭니다. (sessionId를 모를 때는 GET .../lectures/{lectureId}/latest-session 으로 최근 세션 조회)"
    )
    @PostMapping("/{sessionId}/recover")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Map<String, String>> recoverSession(@PathVariable Long sessionId) {
        sessionRecoveryService.recoverGenerationSession(sessionId);
        
        Map<String, String> response = new HashMap<>();
        response.put("message", "세션이 복구되었습니다. 다시 시도할 수 있습니다.");
        return ResponseEntity.ok(response);
    }

    /**
     * 실시간 진행 상황 스트리밍 (SSE)
     * 
     * 엔드포인트: GET /api/materials/generation/{sessionId}/progress
     * 
     * 설명:
     * - Server-Sent Events (SSE)를 통해 실시간으로 진행 상황을 전달합니다.
     * - Redis Pub/Sub을 통해 MaterialGenerationService에서 발행한 진행 상황을 수신합니다.
     * 
     * 응답 형식 (SSE):
     * event: progress
     * data: {"progress": 50, "message": "콘텐츠 생성 중...", "phase": "PHASE3"}
     */
    @Operation(
            summary = "실시간 진행 상황 스트리밍",
            description = "SSE를 통해 강의 자료 생성 진행 상황을 실시간으로 수신합니다."
    )
    @GetMapping(value = "/{sessionId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('TEACHER')")
    public SseEmitter streamProgress(@PathVariable Long sessionId) {
        // SSE Emitter 생성 (1시간 타임아웃)
        SseEmitter emitter = new SseEmitter(3600000L);
        
        // 리스너에 등록
        progressListener.registerEmitter(sessionId.toString(), emitter);
        log.info("SSE 연결 생성: sessionId={}", sessionId);
        
        return emitter;
    }
}
