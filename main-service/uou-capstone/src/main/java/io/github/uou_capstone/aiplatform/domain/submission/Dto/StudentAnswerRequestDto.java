package io.github.uou_capstone.aiplatform.domain.submission.Dto;


import lombok.Getter;

@Getter
public class StudentAnswerRequestDto { //개별 답안 하나

    private Long questionId;
    private Long choiceOptionId;      // 객관식/OX 답안 ID
    private String descriptiveAnswer; // 서술형 답안
}
