package io.github.uou_capstone.aiplatform.domain.course.lecture.dto;

import lombok.Getter;

@Getter
public class AiQuestionAnswerRequestDto {
    private final String stage;
    private final Payload payload;

    // 내부 Payload 클래스
    @Getter
    private static class Payload {
        private final Long lectureId;
        private final String aiQuestionId;  // AI 생성 질문 ID
        private final String answer;

        public Payload(Long lectureId, String aiQuestionId, String answer) {
            this.lectureId = lectureId;
            this.aiQuestionId = aiQuestionId;
            this.answer = answer;
        }
    }

    // 생성자
    public AiQuestionAnswerRequestDto(Long lectureId, String aiQuestionId, String answer) {
        this.stage = "answer_question"; // 👈 AI팀과 협의된 stage
        this.payload = new Payload(lectureId, aiQuestionId, answer);
    }
}