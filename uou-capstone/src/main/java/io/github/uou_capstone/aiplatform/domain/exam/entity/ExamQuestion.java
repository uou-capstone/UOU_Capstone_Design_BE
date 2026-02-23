package io.github.uou_capstone.aiplatform.domain.exam.entity;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.assessment.entity.Assessment;
import io.github.uou_capstone.aiplatform.domain.assessment.entity.CreatedBy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.Set;

/**
 * 시험 문제
 * v2 기준으로 새로 설계 (v1에서는 로직 미구현)
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "exam_questions")
public class ExamQuestion extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "exam_question_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assessment_id", nullable = false)
    private Assessment assessment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_session_id")
    private ExamSession examSession;  // 버전 2 ExamSession 참조 (NULL 허용)

    @Enumerated(EnumType.STRING)
    @Column(name = "exam_type", nullable = false)
    private ExamType examType;  // FLASH_CARD, OX_PROBLEM, FIVE_CHOICE, SHORT_ANSWER, DEBATE

    @Column(name = "question_order")
    private Integer questionOrder;  // 문제 순서 (1, 2, 3, ...)

    @Lob
    @Column(name = "question_content", nullable = false, columnDefinition = "TEXT")
    private String questionContent;  // 문제 발문

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "question_metadata", columnDefinition = "JSON")
    private Map<String, Object> questionMetadata;  // 시험 유형별 메타데이터

    @Enumerated(EnumType.STRING)
    @Column(name = "created_by", nullable = false)
    private CreatedBy createdBy = CreatedBy.AI;  // AI, TEACHER

    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<io.github.uou_capstone.aiplatform.domain.assessment.entity.ChoiceOption> choiceOptions;  // FIVE_CHOICE 유형용

    @Builder
    public ExamQuestion(
            Assessment assessment,
            io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession examSession,
            ExamType examType,
            Integer questionOrder,
            String questionContent,
            Map<String, Object> questionMetadata,
            CreatedBy createdBy
    ) {
        this.assessment = assessment;
        this.examSession = examSession;
        this.examType = examType;
        this.questionOrder = questionOrder;
        this.questionContent = questionContent;
        this.questionMetadata = questionMetadata;
        this.createdBy = createdBy != null ? createdBy : CreatedBy.AI;
    }

    public void updateQuestionContent(String questionContent) {
        this.questionContent = questionContent;
    }

    public void updateQuestionMetadata(Map<String, Object> questionMetadata) {
        this.questionMetadata = questionMetadata;
    }

    public void updateQuestionOrder(Integer questionOrder) {
        this.questionOrder = questionOrder;
    }
}
