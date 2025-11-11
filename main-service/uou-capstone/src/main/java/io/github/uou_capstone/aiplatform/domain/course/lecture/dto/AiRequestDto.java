package io.github.uou_capstone.aiplatform.domain.course.lecture.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Map;


/**
 * (Spring → FastAPI 로 보낼 요청)
 * // 예시: AI가 처리할 PDF 파일의 경로
 * (실제로는 파일 자체를 보낼 수도 있습니다)
 */
@Getter
public class AiRequestDto {

    private final String stage;
    private final Map<String, Object> payload; // ✅ String이 아니라 Object

    // 생성자
    public AiRequestDto(Long lectureId, String pdfPath) {
        this.stage = "run_all_with_callback";
        this.payload = Map.of(
                "lectureId", lectureId,   // ✅ 콜백을 위한 lectureId
                "pdf_path", pdfPath
        );
    }
}