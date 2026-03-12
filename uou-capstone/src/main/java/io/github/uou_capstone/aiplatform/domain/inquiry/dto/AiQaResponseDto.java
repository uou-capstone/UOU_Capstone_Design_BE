package io.github.uou_capstone.aiplatform.domain.inquiry.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
public class AiQaResponseDto {
    private String status;       // "GOOD" 또는 "BAD"
    private String explanation;  // GOOD일 때의 해설
    private List<RemedialStep> steps; // BAD일 때의 하위 개념 질문 리스트

    @Getter
    @NoArgsConstructor
    public static class RemedialStep {
        private String concept;
        private String explanation;
        private String question;
    }
}
