package io.github.uou_capstone.aiplatform.domain.course.lecture;

import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.AiResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "AI 콜백(웹훅) API", description = "AI 서비스가 작업을 완료한 후 호출하는 API")
@RestController
@RequestMapping("/api/ai/callback")
@RequiredArgsConstructor
public class AiCallbackController {

    private final LectureService lectureService;

    //비밀키 주입
    @Value("${ai.service.secret-key}")
    private String aiServiceSecretKey;

    @Operation(summary = "AI 콘텐츠 생성 완료 콜백", description = "ai-service가 콘텐츠 생성을 완료하면 이 API를 호출하여 결과를 전달합니다.")
    @PostMapping("/lectures/{lectureId}")
    public ResponseEntity<String> onAiContentGenerated(
            @PathVariable Long lectureId,
            @RequestBody List<AiResponseDto> aiResults, HttpServletRequest request) { // AI가 보내준 결과

        String secretKeyHeader = request.getHeader("X-AI-SECRET-KEY");
        if (secretKeyHeader == null || !secretKeyHeader.equals(aiServiceSecretKey)) {
            // 비밀키가 없거나 일치하지 않으면 403 Forbidden 반환
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid secret key");
        }

        lectureService.saveAiContentCallback(lectureId, aiResults);
        return ResponseEntity.ok("Callback received successfully.");
    }
}