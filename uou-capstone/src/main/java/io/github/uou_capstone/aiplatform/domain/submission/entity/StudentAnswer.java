package io.github.uou_capstone.aiplatform.domain.submission.entity;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.assessment.entity.ChoiceOption;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamQuestion;
import io.github.uou_capstone.aiplatform.domain.submission.entity.Submission;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "student_answers")
public class StudentAnswer extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "answer_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id", nullable = false)
    private Submission submission;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_question_id", nullable = false)
    private ExamQuestion question;  // v2: ExamQuestion 사용

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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feedback_json", columnDefinition = "JSON")
    private Map<String, Object> feedbackJson;  // AI 생성 피드백 (MySQL JSON)

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evaluation_metadata", columnDefinition = "JSON")
    private Map<String, Object> evaluationMetadata;  // 채점 메타데이터 (MySQL JSON)

    @Builder
    public StudentAnswer(Submission submission, ExamQuestion question, ChoiceOption choiceOption, String descriptiveAnswer) {
        this.submission = submission;
        this.question = question;
        this.choiceOption = choiceOption;
        this.descriptiveAnswer = descriptiveAnswer;
    }

    public void updateFeedback(Map<String, Object> feedbackJson) {
        this.feedbackJson = feedbackJson;
    }

    public void updateEvaluationMetadata(Map<String, Object> evaluationMetadata) {
        this.evaluationMetadata = evaluationMetadata;
    }

    public void updateScore(Boolean isCorrect, Integer score) {
        this.isCorrect = isCorrect;
        this.score = score;
    }
}