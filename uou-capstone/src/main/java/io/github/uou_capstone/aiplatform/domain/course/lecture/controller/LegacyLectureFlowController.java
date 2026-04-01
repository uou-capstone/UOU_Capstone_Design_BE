package io.github.uou_capstone.aiplatform.domain.course.lecture.controller;

import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.LectureStreamAnswerRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.StreamingAnswerResponse;
import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.StreamingInitializeResponse;
import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.StreamingSessionDto;
import io.github.uou_capstone.aiplatform.domain.course.lecture.service.LegacyLectureFlowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

@Tag(name = "강의 AI Legacy API", description = "v1 legacy 강의 AI 흐름 API (유지보수 전용, 신규 기능 추가 금지)")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LegacyLectureFlowController {

    private final LegacyLectureFlowService legacyLectureFlowService;

    @Operation(summary = "AI 강의 콘텐츠 생성", description = "특정 강의의 PDF를 기반으로 AI 콘텐츠 생성을 요청합니다.")
    @PostMapping("/lectures/{lectureId}/generate-content")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<String> generateAiContent(@PathVariable Long lectureId) {
        legacyLectureFlowService.generateAiContent(lectureId);
        return ResponseEntity.ok("AI 콘텐츠 생성 작업이 시작되었습니다.");
    }

    @Operation(summary = "AI 콘텐츠 생성 상태 조회", description = "AI 작업 상태를 조회합니다. (PROCESSING, COMPLETED, FAILED)")
    @GetMapping("/lectures/{lectureId}/ai-status")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<Map<String, String>> getAiContentStatus(@PathVariable Long lectureId) {
        String status = legacyLectureFlowService.getLectureAiStatus(lectureId);
        return ResponseEntity.ok(Map.of("status", status));
    }

    @Operation(summary = "AI 스트리밍 초기화", description = "스트리밍 모드를 시작하기 위해 PDF 분석을 수행하고 세션을 초기화합니다.")
    @PostMapping("/lectures/{lectureId}/stream/initialize")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<StreamingInitializeResponse> initializeLectureStream(@PathVariable Long lectureId) {
        StreamingInitializeResponse response = legacyLectureFlowService.initializeLectureStream(lectureId);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "AI 스트리밍 다음 콘텐츠 (SSE)",
            description = """
                    FastAPI NDJSON 청크를 실시간으로 SSE 이벤트로 중계합니다.

                    이벤트 종류:
                    - event=message : {"type":"delta","delta":"텍스트 조각"}
                    - event=done    : {"type":"done","lectureId":N,"hasMore":false,"waitingForAnswer":false}
                    - event=done    : {"type":"done","status":"WAITING_FOR_ANSWER","waitingForAnswer":true,...}
                    - event=error   : {"type":"error","message":"..."}
                    """)
    @RequestMapping(
            value = "/lectures/{lectureId}/stream/next",
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public Flux<ServerSentEvent<Map<String, Object>>> streamNextLectureContent(@PathVariable Long lectureId) {
        return legacyLectureFlowService.streamNextContent(lectureId);
    }

    @Operation(summary = "AI 스트리밍 세션 조회", description = "현재 스트리밍 세션 정보를 조회합니다.")
    @GetMapping("/lectures/{lectureId}/stream/session")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<StreamingSessionDto> getLectureStreamSession(@PathVariable Long lectureId) {
        StreamingSessionDto response = legacyLectureFlowService.getLectureStreamSession(lectureId);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "AI 스트리밍 질문 답변", description = "스트리밍 중 AI가 제시한 질문에 대한 사용자의 답변을 전송하고 보충 설명을 받습니다.")
    @PostMapping("/lectures/{lectureId}/stream/answer")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<StreamingAnswerResponse> answerLectureStreamQuestion(
            @PathVariable Long lectureId,
            @Valid @RequestBody LectureStreamAnswerRequestDto requestDto) {
        StreamingAnswerResponse response = legacyLectureFlowService.answerLectureStreamQuestion(lectureId, requestDto);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "AI 스트리밍 취소", description = "진행 중인 스트리밍 세션을 취소합니다.")
    @PostMapping("/lectures/{lectureId}/stream/cancel")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<Void> cancelLectureStream(@PathVariable Long lectureId) {
        legacyLectureFlowService.cancelLectureStream(lectureId);
        return ResponseEntity.noContent().build();
    }
}

