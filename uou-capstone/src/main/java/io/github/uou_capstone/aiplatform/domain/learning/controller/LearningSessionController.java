package io.github.uou_capstone.aiplatform.domain.learning.controller;

import io.github.uou_capstone.aiplatform.domain.learning.dto.SessionEventRequest;
import io.github.uou_capstone.aiplatform.domain.learning.service.LearningSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 학습 세션 API Controller (v3)
 *
 * v3 오케스트레이션 엔진(FastAPI MergeEduAgent)과 연동하는 학습 세션 API.
 * 기존 시험 생성 API(/api/exams/*)와 완전히 독립적이며, 외부 API 호환성에 영향을 주지 않는다.
 *
 * 엔드포인트:
 * - POST /api/learning/sessions/{lectureId}         세션 조회 또는 생성
 * - POST /api/learning/sessions/{sessionId}/event   이벤트 전송 (SSE 스트리밍)
 */
@Tag(name = "학습 세션 API (v3)", description = "MergeEduAgent 오케스트레이션 엔진과 연동하는 학습 세션 API")
@RestController
@RequestMapping("/api/learning/sessions")
@RequiredArgsConstructor
public class LearningSessionController {

    private final LearningSessionService learningSessionService;

    /**
     * 학습 세션 조회 또는 생성
     *
     * 엔드포인트: POST /api/learning/sessions/{lectureId}
     *
     * 강의 ID로 기존 학습 세션을 조회하거나 신규 세션을 생성한다.
     * 내부적으로 FastAPI GET /api/session/by-lecture/{lectureId} 를 호출한다.
     *
     * 응답 예시:
     * {
     *   "sessionId": "abc123",
     *   "lectureId": 1,
     *   "state": { "currentPage": 1, "messages": [], "quizzes": [] },
     *   "aiStatus": { "connected": true }
     * }
     */
    @Operation(
            summary = "학습 세션 조회/생성",
            description = "강의 ID로 기존 학습 세션을 조회하거나 신규 세션을 생성합니다. FastAPI MergeEduAgent와 연동됩니다."
    )
    @PostMapping("/{lectureId}")
    @PreAuthorize("hasAuthority('STUDENT') or hasAuthority('TEACHER')")
    public Mono<ResponseEntity<Map<String, Object>>> createOrGetSession(
            @Parameter(description = "강의 ID") @PathVariable Long lectureId) {
        return learningSessionService.getOrCreateSession(lectureId)
                .map(ResponseEntity::ok);
    }

    /**
     * 학습 세션 이벤트 전송 (SSE 스트리밍)
     *
     * 엔드포인트: POST /api/learning/sessions/{sessionId}/event
     *
     * 학습 세션에 AppEvent를 전송하고, FastAPI OrchestrationEngine의 처리 결과를
     * SSE(Server-Sent Events) 스트리밍으로 실시간 수신한다.
     *
     * 요청 예시:
     * { "type": "USER_MESSAGE", "text": "이 페이지 설명해줘" }
     * { "type": "QUIZ_TYPE_SELECTED", "quizType": "MCQ" }
     * { "type": "QUIZ_SUBMITTED", "answers": { "q1": "A", "q2": "B" } }
     *
     * SSE 응답 포맷 (NDJSON 라인별 data 필드):
     * data: {"type":"agent_delta","agent":"explainer","delta":"설명 텍스트..."}
     * data: {"type":"done","agent":"explainer","tool":"EXPLAIN_PAGE","final":true,"data":{}}
     * data: {"type":"error","message":"오류 내용"}
     */
    @Operation(
            summary = "학습 세션 이벤트 전송 (SSE 스트리밍)",
            description = "AppEvent를 전송하고 FastAPI OrchestrationEngine의 처리 결과를 SSE로 실시간 수신합니다."
    )
    @PostMapping(value = "/{sessionId}/event", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('STUDENT') or hasAuthority('TEACHER')")
    public Flux<ServerSentEvent<String>> sendEvent(
            @Parameter(description = "세션 ID (FastAPI 세션 식별자)") @PathVariable String sessionId,
            @Valid @RequestBody SessionEventRequest eventRequest) {
        return learningSessionService.streamSessionEvent(sessionId, eventRequest);
    }
}
