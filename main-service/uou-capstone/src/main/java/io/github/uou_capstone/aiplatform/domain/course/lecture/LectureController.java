package io.github.uou_capstone.aiplatform.domain.course.lecture;

import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.LectureCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.LectureDetailResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.LectureResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.LectureStreamAnswerRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.LectureUpdateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.StreamingAnswerResponse;
import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.StreamingContentResponse;
import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.StreamingInitializeResponse;
import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.StreamingSessionDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "강의 API", description = "강의 생성, AI 콘텐츠 생성 등 강의 관련 API")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LectureController {

    private final LectureService lectureService;

    @Operation(summary = "강의 생성", description = "특정 과목에 새로운 강의를 생성합니다.")
    @PostMapping("/courses/{courseId}/lectures")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<LectureResponseDto> createLecture(
            @PathVariable Long courseId,
            @RequestBody LectureCreateRequestDto requestDto) {

        Lecture newLecture = lectureService.createLecture(courseId, requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(new LectureResponseDto(newLecture));
    }

    @Operation(summary = "강의 상세 조회", description = "특정 강의의 상세 정보와 AI 생성 콘텐츠 목록을 조회합니다.")
    @GetMapping("/lectures/{lectureId}")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<LectureDetailResponseDto> getLectureDetail(@PathVariable Long lectureId) {
        LectureDetailResponseDto lectureDetail = lectureService.getLectureDetail(lectureId);
        return ResponseEntity.ok(lectureDetail);
    }

    @Operation(summary = "강의 정보 수정", description = "선생님이 특정 강의의 정보를 수정합니다.")
    @PutMapping("/lectures/{lectureId}")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<LectureResponseDto> updateLecture(
            @PathVariable Long lectureId,
            @RequestBody LectureUpdateRequestDto requestDto) {

        Lecture updatedLecture = lectureService.updateLecture(lectureId, requestDto);
        return ResponseEntity.ok(new LectureResponseDto(updatedLecture));
    }

    @Operation(summary = "강의 삭제", description = "선생님이 특정 강의를 삭제합니다.")
    @DeleteMapping("/lectures/{lectureId}")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Void> deleteLecture(@PathVariable Long lectureId) {
        lectureService.deleteLecture(lectureId);

        // 삭제 성공 시 204 No Content 응답 반환
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "AI 강의 콘텐츠 생성", description = "특정 강의의 PDF를 기반으로 AI 콘텐츠(대본, 요약 등) 생성을 요청합니다.")
    @PostMapping("/lectures/{lectureId}/generate-content")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<String> generateAiContent(@PathVariable Long lectureId) {
        lectureService.generateAiContent(lectureId);
        return ResponseEntity.ok("AI 콘텐츠 생성 작업이 시작되었습니다.");
    }

    // AI 작업 상태 폴링(Polling) API
    @Operation(summary = "AI 콘텐츠 생성 상태 조회", description = "AI 작업 상태를 조회합니다. (PROCESSING, COMPLETED, FAILED)")
    @GetMapping("/lectures/{lectureId}/ai-status")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<Map<String, String>> getAiContentStatus(@PathVariable Long lectureId) {
        String status = lectureService.getLectureAiStatus(lectureId);
        return ResponseEntity.ok(Map.of("status", status));
    }

    @Operation(summary = "AI 스트리밍 초기화", description = "스트리밍 모드를 시작하기 위해 PDF 분석을 수행하고 세션을 초기화합니다.")
    @PostMapping("/lectures/{lectureId}/stream/initialize")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<StreamingInitializeResponse> initializeLectureStream(@PathVariable Long lectureId) {
        StreamingInitializeResponse response = lectureService.initializeLectureStream(lectureId);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "AI 스트리밍 다음 콘텐츠", description = "스트리밍 세션에서 다음 콘텐츠 세그먼트를 가져옵니다.")
    @PostMapping("/lectures/{lectureId}/stream/next")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<StreamingContentResponse> getNextLectureStreamContent(@PathVariable Long lectureId) {
        StreamingContentResponse response = lectureService.getNextLectureStreamContent(lectureId);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "AI 스트리밍 세션 조회", description = "현재 스트리밍 세션 정보를 조회합니다.")
    @GetMapping("/lectures/{lectureId}/stream/session")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<StreamingSessionDto> getLectureStreamSession(@PathVariable Long lectureId) {
        StreamingSessionDto response = lectureService.getLectureStreamSession(lectureId);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "AI 스트리밍 질문 답변", description = "스트리밍 중 AI가 제시한 질문에 대한 사용자의 답변을 전송하고 보충 설명을 받습니다.")
    @PostMapping("/lectures/{lectureId}/stream/answer")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<StreamingAnswerResponse> answerLectureStreamQuestion(
            @PathVariable Long lectureId,
            @Valid @RequestBody LectureStreamAnswerRequestDto requestDto) {
        StreamingAnswerResponse response = lectureService.answerLectureStreamQuestion(lectureId, requestDto);
        return ResponseEntity.ok(response);
    }
}