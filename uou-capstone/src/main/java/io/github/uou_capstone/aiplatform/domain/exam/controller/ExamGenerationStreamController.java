package io.github.uou_capstone.aiplatform.domain.exam.controller;

import io.github.uou_capstone.aiplatform.agent.StreamingEvent;
import io.github.uou_capstone.aiplatform.domain.exam.service.ExamGenerationStreamService;
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
 * 시험 생성 스트리밍 Controller — Agent 추론 과정을 실시간 SSE 로 중계.
 *
 * SSE 이벤트 표준(2026-04 확정): message / heartbeat / timeout / error / done.
 */
@Slf4j
@Tag(name = "시험 생성 스트리밍 API", description = "AI Agent 추론 과정 실시간 스트리밍 API")
@RestController
@RequestMapping("/api/exams/generation")
@RequiredArgsConstructor
public class ExamGenerationStreamController {

    private final ExamGenerationStreamService streamService;

    @Operation(summary = "시험 생성 스트리밍",
            description = "시험 생성 Agent의 추론 과정을 실시간으로 스트리밍합니다. 시험 유형에 따라 다른 Agent가 실행됩니다.")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('TEACHER')")
    public Flux<ServerSentEvent<StreamingEvent>> streamExamGeneration(@RequestParam Long examSessionId) {
        Flux<ServerSentEvent<StreamingEvent>> source = streamService.streamExamGeneration(examSessionId)
                .map(event -> ServerSentEvent.<StreamingEvent>builder()
                        .event(SseEventNames.MESSAGE)
                        .data(event)
                        .build());

        Flux<ServerSentEvent<StreamingEvent>> wrapped = SseStreamSupport.wrapEvents(
                source,
                SseStreamPolicy.defaults(),
                error -> {
                    log.error("시험 생성 스트리밍 오류: examSessionId={}", examSessionId, error);
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
