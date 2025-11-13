package io.github.uou_capstone.aiplatform.domain.course.lecture.dto;

import lombok.Getter;

import java.util.Map;

/**
 * AI Service (FastAPI)의 /api/delegator/dispatch 엔드포인트로 보낼 요청 DTO
 * FastAPI의 DelegatorDispatchRequest 형식에 맞춘 요청
 */
@Getter
public class AiRequestDto {

    private final String stage;
    private final Map<String, Object> payload;

    /**
     * AI 강의 콘텐츠 생성용 생성자
     * @param lectureId 웹훅 콜백을 위한 강의 ID
     * @param pdfPath ai-service에 전달할 파일 경로
     */
    public AiRequestDto(Long lectureId, String pdfPath) {
        this.stage = "run_all";  // README에 명시된 대로 "run_all" 사용
        this.payload = Map.of(
                "lectureId", lectureId,  // 웹훅 콜백을 위한 lectureId
                "pdf_path", pdfPath
        );
    }
}
