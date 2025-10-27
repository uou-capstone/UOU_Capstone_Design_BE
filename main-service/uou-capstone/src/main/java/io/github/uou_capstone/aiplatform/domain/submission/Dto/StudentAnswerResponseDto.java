package io.github.uou_capstone.aiplatform.domain.submission.Dto;

import io.github.uou_capstone.aiplatform.domain.assessment.QuestionType;
import io.github.uou_capstone.aiplatform.domain.submission.StudentAnswer;
import lombok.Getter;

@Getter
public class StudentAnswerResponseDto {
    private final Long questionId;
    private final String questionText;
    private final QuestionType questionType;
    private final Long choiceOptionId;      // 학생이 선택한 선택지 ID
    private final String choiceOptionText;    // 학생이 선택한 선택지 텍스트
    private final String descriptiveAnswer; // 학생이 작성한 서술형 답안
    private final Boolean isCorrect;         // 채점 결과 (null 가능)
    private final Integer score;             // 득점 (null 가능)
    private final String teacherComment;      // 선생님 코멘트 (null 가능)

    // 생성자: StudentAnswer 엔티티를 받아서 DTO 필드를 채움
    public StudentAnswerResponseDto(StudentAnswer answer) {
        this.questionId = answer.getQuestion().getId();
        this.questionText = answer.getQuestion().getText();
        this.questionType = answer.getQuestion().getType();
        // choiceOption 필드를 사용
        this.choiceOptionId = (answer.getChoiceOption() != null) ? answer.getChoiceOption().getId() : null;
        this.choiceOptionText = (answer.getChoiceOption() != null) ? answer.getChoiceOption().getText() : null;
        this.descriptiveAnswer = answer.getDescriptiveAnswer();
        this.isCorrect = answer.getIsCorrect();
        this.score = answer.getScore();
        this.teacherComment = answer.getTeacherComment();
    }
}