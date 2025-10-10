package io.github.uou_capstone.aiplatform.domain.assessment;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "questions")
public class Question extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "question_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assessment_id", nullable = false)
    private Assessment assessment;

    @Lob
    @Column(name = "question_text", nullable = false)
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_type", nullable = false)
    private QuestionType type; // 퀴즈 종류 flash, ox, 객관식

    @Enumerated(EnumType.STRING)
    @Column(name = "created_by", nullable = false)
    private CreatedBy createdBy;

    @Builder
    public Question(Assessment assessment, String text,  QuestionType type,  CreatedBy createdBy) {
        this.assessment = assessment;
        this.text = text;
        this.type = type;
        this.createdBy = createdBy;
    }
}