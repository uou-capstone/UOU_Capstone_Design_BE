package io.github.uou_capstone.aiplatform.domain.material.generation.controller;

import io.github.uou_capstone.aiplatform.agent.StreamingEvent;
import io.github.uou_capstone.aiplatform.domain.material.generation.service.MaterialGenerationStreamService;
import io.github.uou_capstone.aiplatform.util.sse.SseEventNames;
import io.github.uou_capstone.aiplatform.util.sse.SseStreamPolicy;
import io.github.uou_capstone.aiplatform.util.sse.SseStreamSupport;
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
 * 강의 자료 생성 스트리밍 Controller — Agent 추론 과정을 실시간 SSE 로 중계.
 *
 * SSE 이벤트 표준(2026-04 확정):
 *  - event:message  : Agent 추론 델타
 *  - event:timeout  : 서버측 idle timeout (FE 재접속 신호)
 *  - event:error    : 에러
 *  - event:done     : 정상 종료 (FE 재접속 금지)
 *
 * 5 개 phase 모두 동일 패턴이라 {@link #toSseEvents(Flux, String)} 헬퍼로 통합.
 */
@Slf4j
@Tag(name = "강의 자료 생성 스트리밍 API", description = "AI Agent 추론 과정 실시간 스트리밍 API")
@RestController
@RequestMapping("/api/materials/generation")
@RequiredArgsConstructor
public class MaterialGenerationStreamController {

    private final MaterialGenerationStreamService streamService;

    @Operation(summary = "Phase 1 스트리밍", description = "PlanningAgent의 추론 과정을 실시간으로 스트리밍합니다.")
    @GetMapping(value = "/phase1/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('TEACHER')")
    public Flux<ServerSentEvent<StreamingEvent>> streamPhase1(@RequestParam Long sessionId) {
        return toSseEvents(streamService.streamPhase1(sessionId), "Phase 1");
    }

    @Operation(summary = "Phase 2 스트리밍", description = "ConfirmAgent의 추론 과정을 실시간으로 스트리밍합니다.")
    @GetMapping(value = "/phase2/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('TEACHER')")
    public Flux<ServerSentEvent<StreamingEvent>> streamPhase2(
            @RequestParam Long sessionId,
            @RequestParam(required = false) String userFeedback) {
        return toSseEvents(streamService.streamPhase2(sessionId, userFeedback), "Phase 2");
    }

    @Operation(summary = "Phase 3 스트리밍", description = "DecompositionAgent와 WriteAgent의 추론 과정을 실시간으로 스트리밍합니다.")
    @GetMapping(value = "/phase3/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('TEACHER')")
    public Flux<ServerSentEvent<StreamingEvent>> streamPhase3(@RequestParam Long sessionId) {
        return toSseEvents(streamService.streamPhase3(sessionId), "Phase 3");
    }

    @Operation(summary = "Phase 4 스트리밍", description = "ValidationAgent와 ReviewAgent의 추론 과정을 실시간으로 스트리밍합니다.")
    @GetMapping(value = "/phase4/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('TEACHER')")
    public Flux<ServerSentEvent<StreamingEvent>> streamPhase4(@RequestParam Long sessionId) {
        return toSseEvents(streamService.streamPhase4(sessionId), "Phase 4");
    }

    @Operation(summary = "Phase 5 스트리밍", description = "EditorAgent의 추론 과정을 실시간으로 스트리밍합니다.")
    @GetMapping(value = "/phase5/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('TEACHER')")
    public Flux<ServerSentEvent<StreamingEvent>> streamPhase5(@RequestParam Long sessionId) {
        return toSseEvents(streamService.streamPhase5(sessionId), "Phase 5");
    }

    private Flux<ServerSentEvent<StreamingEvent>> toSseEvents(Flux<StreamingEvent> source, String phaseName) {
        Flux<ServerSentEvent<StreamingEvent>> events = source.map(event ->
                ServerSentEvent.<StreamingEvent>builder()
                        .event(SseEventNames.MESSAGE)
                        .data(event)
                        .build()
        );

        Flux<ServerSentEvent<StreamingEvent>> wrapped = SseStreamSupport.wrapEvents(
                events,
                SseStreamPolicy.defaults(),
                error -> {
                    log.error("{} streaming error: {}", phaseName, error.getMessage(), error);
                    String msg = error.getMessage() != null ? error.getMessage() : "알 수 없는 오류";
                    return SseStreamSupport.error(StreamingEvent.builder()
                            .type("error")
                            .delta("스트리밍 중 오류가 발생했습니다: " + msg)
                            .build());
                }
        );

        return wrapped.concatWith(Flux.just(SseStreamSupport.done(
                StreamingEvent.builder().type("done").build()
        )));
    }
}
