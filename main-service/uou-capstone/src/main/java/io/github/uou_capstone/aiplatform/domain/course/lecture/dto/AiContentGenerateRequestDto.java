package io.github.uou_capstone.aiplatform.domain.course.lecture.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

@Getter
public class AiContentGenerateRequestDto {  // ai 강의 대본 생성
    private final String stage;
    private final Payload payload;

    // 내부 Payload 클래스
    @Getter
    private static class Payload {
        private final Long lectureId;
        @JsonProperty("pdf_path") // 👈 JSON 필드명 지정
        private final String pdfPath;

        public Payload(Long lectureId, String pdfPath) {
            this.lectureId = lectureId;
            this.pdfPath = pdfPath;
        }
    }

    // 생성자
    public AiContentGenerateRequestDto(Long lectureId, String pdfPath) {
        this.stage = "pdf_processing"; // AI 콘텐츠 생성용 stage (run_all과 동일하게 동작)
        this.payload = new Payload(lectureId, pdfPath);
    }
}