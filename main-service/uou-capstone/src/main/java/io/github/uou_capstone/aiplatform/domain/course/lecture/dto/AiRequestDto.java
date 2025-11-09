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
    private final Map<String, String> payload; // "payload": {"pdf_path": "..."}를 만들기 위함

    /**
     * FastAPI의 DelegatorDispatchRequest 형식에 맞춘 요청 DTO 생성자
     * @param pdfPath ai-service에 전달할 파일 경로
     */
    public AiRequestDto(String pdfPath) {
        this.stage = "pdf_processing"; // FastAPI에 전달할 stage 값 (임의 지정)
        this.payload = Map.of("pdf_path", pdfPath); // payload 맵 생성
    }
}