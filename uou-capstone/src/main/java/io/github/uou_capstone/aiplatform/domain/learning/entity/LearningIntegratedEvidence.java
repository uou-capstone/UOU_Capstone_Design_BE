package io.github.uou_capstone.aiplatform.domain.learning.entity;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.material.entity.Material;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "learning_integrated_evidence",
        indexes = {
                @Index(name = "idx_learning_evidence_user_lecture_material", columnList = "user_id, lecture_id, material_id"),
                @Index(name = "idx_learning_evidence_lecture_user", columnList = "lecture_id, user_id"),
                @Index(name = "idx_learning_evidence_session", columnList = "chat_session_id"),
                @Index(name = "idx_learning_evidence_created", columnList = "created_at")
        })
public class LearningIntegratedEvidence extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "learning_evidence_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_session_id", nullable = false)
    private LearningChatSession chatSession;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lecture_id", nullable = false)
    private Lecture lecture;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id")
    private Material material;

    @Column(name = "event_kind", length = 100)
    private String eventKind;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "coverage_start_page")
    private Integer coverageStartPage;

    @Column(name = "coverage_end_page")
    private Integer coverageEndPage;

    @Column(name = "quiz_id", length = 255)
    private String quizId;

    @Column(name = "quiz_type", length = 100)
    private String quizType;

    @Column(name = "score_ratio")
    private Double scoreRatio;

    @Column(name = "passed")
    private Boolean passed;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "weak_concepts", columnDefinition = "JSON")
    private List<String> weakConcepts;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "missed_questions", columnDefinition = "JSON")
    private List<Object> missedQuestions;

    @Lob
    @Column(name = "diagnostic_prompt", columnDefinition = "TEXT")
    private String diagnosticPrompt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_evidence", columnDefinition = "JSON")
    private Map<String, Object> rawEvidence;

    @Builder
    public LearningIntegratedEvidence(LearningChatSession chatSession,
                                      User user,
                                      Lecture lecture,
                                      Material material,
                                      String eventKind,
                                      Integer pageNumber,
                                      Integer coverageStartPage,
                                      Integer coverageEndPage,
                                      String quizId,
                                      String quizType,
                                      Double scoreRatio,
                                      Boolean passed,
                                      List<String> weakConcepts,
                                      List<Object> missedQuestions,
                                      String diagnosticPrompt,
                                      Map<String, Object> rawEvidence) {
        this.chatSession = chatSession;
        this.user = user;
        this.lecture = lecture;
        this.material = material;
        this.eventKind = eventKind;
        this.pageNumber = pageNumber;
        this.coverageStartPage = coverageStartPage;
        this.coverageEndPage = coverageEndPage;
        this.quizId = quizId;
        this.quizType = quizType;
        this.scoreRatio = scoreRatio;
        this.passed = passed;
        this.weakConcepts = weakConcepts;
        this.missedQuestions = missedQuestions;
        this.diagnosticPrompt = diagnosticPrompt;
        this.rawEvidence = rawEvidence;
    }
}
