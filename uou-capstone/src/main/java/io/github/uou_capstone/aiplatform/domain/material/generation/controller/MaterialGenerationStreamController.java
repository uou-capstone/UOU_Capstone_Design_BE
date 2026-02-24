package io.github.uou_capstone.aiplatform.domain.material.generation.controller;

import io.github.uou_capstone.aiplatform.agent.StreamingEvent;
import io.github.uou_capstone.aiplatform.domain.material.generation.service.MaterialGenerationStreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 강의 자료 생성 스트리밍 Controller
 * Agent 추론 과정을 실시간으로 스트리밍하는 SSE 엔드포인트
 * 
 * API 엔드포인트:
 * - GET /api/materials/generation/phase1/stream: Phase 1 스트리밍
 * - GET /api/materials/generation/phase2/stream: Phase 2 스트리밍
 * - GET /api/materials/generation/phase3/stream: Phase 3 스트리밍
 * - GET /api/materials/generation/phase4/stream: Phase 4 스트리밍
 * - GET /api/materials/generation/phase5/stream: Phase 5 스트리밍
 */
@Slf4j
@Tag(name = "강의 자료 생성 스트리밍 API", description = "AI Agent 추론 과정 실시간 스트리밍 API")
@RestController
@RequestMapping("/api/materials/generation")
@RequiredArgsConstructor
public class MaterialGenerationStreamController {

    private final MaterialGenerationStreamService streamService;

    /**
     * Phase 1 스트리밍
     * 
     * 엔드포인트: GET /api/materials/generation/phase1/stream?sessionId={sessionId}
     * 
     * 응답 형식 (SSE):
     * event: message
     * data: {"type":"thought","delta":"키워드를 분석 중...","content":{...}}
     * 
     * data: {"type":"thought","delta":"\n초기 계획을 수립 중...","content":{...}}
     * 
     * data: {"type":"answer","delta":"DraftPlan 생성 완료","content":{...}}
     * 
     * 로직 흐름:
     * 1. Controller가 sessionId를 받아 Service에 전달
     * 2. Service가 PlanningAgent.executeStreaming() 호출
     * 3. Agent가 FastAPI의 /stream 엔드포인트 호출
     * 4. FastAPI가 스트리밍 이벤트를 전송
     * 5. Controller가 SSE 형식으로 변환하여 클라이언트에 전송
     */
    @Operation(
            summary = "Phase 1 스트리밍", 
            description = "PlanningAgent의 추론 과정을 실시간으로 스트리밍합니다."
    )
    @GetMapping(value = "/phase1/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('TEACHER')")
    public Flux<ServerSentEvent<StreamingEvent>> streamPhase1(@RequestParam Long sessionId) {
        return streamService.streamPhase1(sessionId)
                .map(event -> ServerSentEvent.<StreamingEvent>builder()
                        .event("message")
                        .data(event)
                        .build())
                .onErrorResume(error -> {
                    log.error("Phase 1 streaming error: {}", error.getMessage(), error);
                    // 에러 이벤트를 보내고 스트림을 완전히 종료
                    return Flux.just(ServerSentEvent.<StreamingEvent>builder()
                            .event("error")
                            .data(StreamingEvent.builder()
                                    .type("error")
                                    .delta("스트리밍 중 오류가 발생했습니다: " + 
                                           (error.getMessage() != null ? error.getMessage() : "알 수 없는 오류"))
                                    .build())
                            .build())
                            .concatWith(Flux.empty()); // 스트림 완전 종료
                });
    }

    /**
     * Phase 2 스트리밍
     * 
     * 엔드포인트: GET /api/materials/generation/phase2/stream?sessionId={sessionId}
     */
    @Operation(
            summary = "Phase 2 스트리밍", 
            description = "ConfirmAgent의 추론 과정을 실시간으로 스트리밍합니다."
    )
    @GetMapping(value = "/phase2/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('TEACHER')")
    public Flux<ServerSentEvent<StreamingEvent>> streamPhase2(
            @RequestParam Long sessionId,
            @RequestParam(required = false) String userFeedback) {
        return streamService.streamPhase2(sessionId, userFeedback)
                .map(event -> ServerSentEvent.<StreamingEvent>builder()
                        .event("message")
                        .data(event)
                        .build())
                .onErrorResume(error -> {
                    log.error("Phase 1 streaming error: {}", error.getMessage(), error);
                    // 에러 이벤트를 보내고 스트림을 완전히 종료
                    return Flux.just(ServerSentEvent.<StreamingEvent>builder()
                            .event("error")
                            .data(StreamingEvent.builder()
                                    .type("error")
                                    .delta("스트리밍 중 오류가 발생했습니다: " + 
                                           (error.getMessage() != null ? error.getMessage() : "알 수 없는 오류"))
                                    .build())
                            .build())
                            .concatWith(Flux.empty()); // 스트림 완전 종료
                });
    }

    /**
     * Phase 3 스트리밍
     * 
     * 엔드포인트: GET /api/materials/generation/phase3/stream?sessionId={sessionId}
     */
    @Operation(
            summary = "Phase 3 스트리밍", 
            description = "DecompositionAgent와 WriteAgent의 추론 과정을 실시간으로 스트리밍합니다."
    )
    @GetMapping(value = "/phase3/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('TEACHER')")
    public Flux<ServerSentEvent<StreamingEvent>> streamPhase3(@RequestParam Long sessionId) {
        return streamService.streamPhase3(sessionId)
                .map(event -> ServerSentEvent.<StreamingEvent>builder()
                        .event("message")
                        .data(event)
                        .build())
                .onErrorResume(error -> {
                    log.error("Phase 1 streaming error: {}", error.getMessage(), error);
                    // 에러 이벤트를 보내고 스트림을 완전히 종료
                    return Flux.just(ServerSentEvent.<StreamingEvent>builder()
                            .event("error")
                            .data(StreamingEvent.builder()
                                    .type("error")
                                    .delta("스트리밍 중 오류가 발생했습니다: " + 
                                           (error.getMessage() != null ? error.getMessage() : "알 수 없는 오류"))
                                    .build())
                            .build())
                            .concatWith(Flux.empty()); // 스트림 완전 종료
                });
    }

    /**
     * Phase 4 스트리밍
     * 
     * 엔드포인트: GET /api/materials/generation/phase4/stream?sessionId={sessionId}
     */
    @Operation(
            summary = "Phase 4 스트리밍", 
            description = "ValidationAgent와 ReviewAgent의 추론 과정을 실시간으로 스트리밍합니다."
    )
    @GetMapping(value = "/phase4/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('TEACHER')")
    public Flux<ServerSentEvent<StreamingEvent>> streamPhase4(@RequestParam Long sessionId) {
        return streamService.streamPhase4(sessionId)
                .map(event -> ServerSentEvent.<StreamingEvent>builder()
                        .event("message")
                        .data(event)
                        .build())
                .onErrorResume(error -> {
                    log.error("Phase 1 streaming error: {}", error.getMessage(), error);
                    // 에러 이벤트를 보내고 스트림을 완전히 종료
                    return Flux.just(ServerSentEvent.<StreamingEvent>builder()
                            .event("error")
                            .data(StreamingEvent.builder()
                                    .type("error")
                                    .delta("스트리밍 중 오류가 발생했습니다: " + 
                                           (error.getMessage() != null ? error.getMessage() : "알 수 없는 오류"))
                                    .build())
                            .build())
                            .concatWith(Flux.empty()); // 스트림 완전 종료
                });
    }

    /**
     * Phase 5 스트리밍
     * 
     * 엔드포인트: GET /api/materials/generation/phase5/stream?sessionId={sessionId}
     */
    @Operation(
            summary = "Phase 5 스트리밍", 
            description = "EditorAgent의 추론 과정을 실시간으로 스트리밍합니다."
    )
    @GetMapping(value = "/phase5/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('TEACHER')")
    public Flux<ServerSentEvent<StreamingEvent>> streamPhase5(@RequestParam Long sessionId) {
        return streamService.streamPhase5(sessionId)
                .map(event -> ServerSentEvent.<StreamingEvent>builder()
                        .event("message")
                        .data(event)
                        .build())
                .onErrorResume(error -> {
                    log.error("Phase 1 streaming error: {}", error.getMessage(), error);
                    // 에러 이벤트를 보내고 스트림을 완전히 종료
                    return Flux.just(ServerSentEvent.<StreamingEvent>builder()
                            .event("error")
                            .data(StreamingEvent.builder()
                                    .type("error")
                                    .delta("스트리밍 중 오류가 발생했습니다: " + 
                                           (error.getMessage() != null ? error.getMessage() : "알 수 없는 오류"))
                                    .build())
                            .build())
                            .concatWith(Flux.empty()); // 스트림 완전 종료
                });
    }
}
