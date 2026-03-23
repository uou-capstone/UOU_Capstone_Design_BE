package io.github.uou_capstone.aiplatform.domain.exam.dto;

import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * 시험 생성 응답 DTO
 * 시험 유형별 문제 리스트를 포함
 */
@Getter
@Builder
public class ExamGenerationResponseDto {
    private Long examSessionId;
    private Long materialId;
    private ExamType examType;
    private List<FlashCardDto> flashCards; // FLASH_CARD 유형일 때
    private List<OxProblemDto> oxProblems; // OX_PROBLEM 유형일 때
    private List<FiveChoiceProblemDto> fiveChoiceProblems; // FIVE_CHOICE 유형일 때
    private List<ShortAnswerProblemDto> shortAnswerProblems; // SHORT_ANSWER 유형일 때
    private List<DebateTopicDto> debateTopics; // DEBATE 유형일 때
    private TestProfileDto usedProfile; // 사용된 프로필
    private Integer totalCount; // 생성된 문제/카드 수
}
