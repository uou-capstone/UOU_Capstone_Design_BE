package io.github.uou_capstone.aiplatform.domain.learning.entity;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.material.entity.Material;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "learning_session_evidence",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_learning_session_evidence_evidence_id", columnNames = "evidence_id")
        },
        indexes = {
                @Index(name = "idx_learning_session_evidence_student_course_time",
                        columnList = "student_id, course_id, occurred_at"),
                @Index(name = "idx_learning_session_evidence_session", columnList = "session_id"),
                @Index(name = "idx_learning_session_evidence_lecture_student", columnList = "lecture_id, student_id")
        })
public class LearningSessionEvidence extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "evidence_id", nullable = false, length = 255)
    private String evidenceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lecture_id", nullable = false)
    private Lecture lecture;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id")
    private Material material;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private LearningChatSession session;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "event_type", length = 100)
    private String eventType;

    @Column(name = "quiz_type", length = 100)
    private String quizType;

    @Column(name = "score_ratio")
    private Double scoreRatio;

    @Column(name = "passed")
    private Boolean passed;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "weak_concepts_json", columnDefinition = "JSON")
    private List<String> weakConcepts;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "wrong_items_json", columnDefinition = "JSON")
    private List<Object> wrongItems;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json", columnDefinition = "JSON")
    private Map<String, Object> evidence;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Builder
    public LearningSessionEvidence(String evidenceId,
                                   Course course,
                                   Lecture lecture,
                                   Material material,
                                   Student student,
                                   LearningChatSession session,
                                   Integer pageNumber,
                                   String eventType,
                                   String quizType,
                                   Double scoreRatio,
                                   Boolean passed,
                                   List<String> weakConcepts,
                                   List<Object> wrongItems,
                                   Map<String, Object> evidence,
                                   LocalDateTime occurredAt) {
        this.evidenceId = evidenceId;
        this.course = course;
        this.lecture = lecture;
        this.material = material;
        this.student = student;
        this.session = session;
        this.pageNumber = pageNumber;
        this.eventType = eventType;
        this.quizType = quizType;
        this.scoreRatio = scoreRatio;
        this.passed = passed;
        this.weakConcepts = weakConcepts;
        this.wrongItems = wrongItems;
        this.evidence = evidence;
        this.occurredAt = occurredAt;
    }
}
