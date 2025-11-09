package io.github.uou_capstone.aiplatform.domain.submission;

import io.github.uou_capstone.aiplatform.domain.assessment.ChoiceOption;
import io.github.uou_capstone.aiplatform.domain.assessment.Question;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "student_answers")
public class StudentAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "answer_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id", nullable = false)
    private Submission submission;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "choice_option_id")
    private ChoiceOption choiceOption; // 객관식 답안

    @Lob
    @Column(name = "descriptive_answer")
    private String descriptiveAnswer; // 서술형 답안

    @Column(name = "is_correct")
    private Boolean isCorrect;

    @Column
    private Integer score;

    @Lob
    @Column(name = "teacher_comment")
    private String teacherComment;

    @Builder
    public StudentAnswer(Submission submission, Question question, ChoiceOption choiceOption, String descriptiveAnswer) {
        this.submission = submission;
        this.question = question;
        this.choiceOption = choiceOption;
        this.descriptiveAnswer = descriptiveAnswer;
    }

}