package io.github.uou_capstone.aiplatform.domain.exam.controller;

import io.github.uou_capstone.aiplatform.agent.StreamingEvent;
import io.github.uou_capstone.aiplatform.domain.exam.service.ExamGenerationStreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 시험 생성 스트리밍 Controller
 * Agent 추론 과정을 실시간으로 스트리밍하는 SSE 엔드포인트
 * 
 * API 엔드포인트:
 * - GET /api/exams/generation/stream: 시험 생성 스트리밍
 */
@Tag(name = "시험 생성 스트리밍 API", description = "AI Agent 추론 과정 실시간 스트리밍 API")
@RestController
@RequestMapping("/api/exams/generation")
@RequiredArgsConstructor
public class ExamGenerationStreamController {

    private final ExamGenerationStreamService streamService;

    /**
     * 시험 생성 스트리밍
     * 
     * 엔드포인트: GET /api/exams/generation/stream?examSessionId={examSessionId}
     * 
     * 응답 형식 (SSE):
     * event: message
     * data: {"type":"thought","delta":"Profile을 생성 중...","content":{...}}
     * 
     * data: {"type":"thought","delta":"\n문제를 생성 중...","content":{...}}
     * 
     * data: {"type":"answer","delta":"시험 생성 완료","content":{...}}
     * 
     * 로직 흐름:
     * 1. Controller가 examSessionId를 받아 Service에 전달
     * 2. Service가 시험 유형에 따라 해당 GeneratorAgent.executeStreaming() 호출
     * 3. Agent가 FastAPI의 /stream 엔드포인트 호출
     * 4. FastAPI가 스트리밍 이벤트를 전송
     * 5. Controller가 SSE 형식으로 변환하여 클라이언트에 전송
     */
    @Operation(
            summary = "시험 생성 스트리밍", 
            description = "시험 생성 Agent의 추론 과정을 실시간으로 스트리밍합니다. 시험 유형에 따라 다른 Agent가 실행됩니다."
    )
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('TEACHER')")
    public Flux<ServerSentEvent<StreamingEvent>> streamExamGeneration(@RequestParam Long examSessionId) {
        return streamService.streamExamGeneration(examSessionId)
                .map(event -> ServerSentEvent.<StreamingEvent>builder()
                        .event("message")
                        .data(event)
                        .build())
                .onErrorResume(error -> {
                    return Flux.just(ServerSentEvent.<StreamingEvent>builder()
                            .event("error")
                            .data(StreamingEvent.builder()
                                    .type("error")
                                    .delta("스트리밍 중 오류가 발생했습니다: " + error.getMessage())
                                    .build())
                            .build());
                });
    }
}
