package io.github.uou_capstone.aiplatform.domain.assessment.dto;

import lombok.Getter;

import java.util.List;

@Getter
public class AiQuizGenerateRequestDto {
    // ai-service의 FastAPI 모델에 맞출 필드
    private final Long assessmentId; // 콜백을 위해 새로 생성한 평가 ID
    private final String pdfPath;
    private final List<String> questionTypes;
    private final int questionCount;

    public AiQuizGenerateRequestDto(Long assessmentId, String pdfPath) {
        this.assessmentId = assessmentId;
        this.pdfPath = pdfPath;
        // 프로토타입용 기본값
        this.questionTypes = List.of("MULTIPLE_CHOICE", "OX", "ESSAY");
        this.questionCount = 5;
    }
}