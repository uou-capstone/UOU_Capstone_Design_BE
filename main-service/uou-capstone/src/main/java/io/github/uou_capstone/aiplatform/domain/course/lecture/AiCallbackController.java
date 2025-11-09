package io.github.uou_capstone.aiplatform.domain.course.lecture;

import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.AiResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "AI 콜백(웹훅) API", description = "AI 서비스가 작업을 완료한 후 호출하는 API")
@RestController
@RequestMapping("/api/ai/callback")
@RequiredArgsConstructor
public class AiCallbackController {

    private final LectureService lectureService;

    @Operation(summary = "AI 콘텐츠 생성 완료 콜백", description = "ai-service가 콘텐츠 생성을 완료하면 이 API를 호출하여 결과를 전달합니다.")
    @PostMapping("/lectures/{lectureId}")
    public ResponseEntity<String> onAiContentGenerated(
            @PathVariable Long lectureId,
            @RequestBody List<AiResponseDto> aiResults) { //  AI가 보내준 결과

        // (보안: 실제로는 ai-service만 호출할 수 있도록 헤더에 secret-key 등을 받아 검증해야 함)

        lectureService.saveAiContentCallback(lectureId, aiResults);
        return ResponseEntity.ok("Callback received successfully.");
    }
}