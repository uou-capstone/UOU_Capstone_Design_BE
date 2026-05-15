package io.github.uou_capstone.aiplatform.domain.exam.dto.student;

import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 학생용 시험 상세 응답.
 *
 * <p>정답·해설·평가 기준 등 학생에게 노출되어서는 안 되는 필드는 구조적으로 포함하지 않는다
 * (별도 DTO 만 사용 → 누출 차단).
 *
 * <p>examType 에 따라 아래 5개 리스트 중 정확히 하나가 채워진다. DEBATE 는 현재 빈 리스트가
 * 내려간다 (실제 토론 진입은 {@code /api/exams/debate/start}).
 */
@Getter
@Builder
public class StudentExamDetailDto {
    private Long examSessionId;
    private Long materialId;
    private ExamType examType;
    private String displayName;
    private int totalCount;

    private List<StudentFlashCardDto> flashCards;
    private List<StudentOxProblemDto> oxProblems;
    private List<StudentFiveChoiceProblemDto> fiveChoiceProblems;
    private List<StudentShortAnswerProblemDto> shortAnswerProblems;
    private List<StudentDebateTopicDto> debateTopics;
}
