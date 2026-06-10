package io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.entity;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "student_report_analyses",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_sra_course_student",
                columnNames = {"course_id", "student_id"}))
public class StudentReportAnalysis extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "student_report_analysis_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Column(name = "analysis_json", columnDefinition = "LONGTEXT", nullable = false)
    private String analysisJson;

    @Column(name = "summary_markdown", columnDefinition = "LONGTEXT")
    private String summaryMarkdown;

    @Column(length = 100)
    private String source;

    @Column(name = "fallback_used", nullable = false)
    private boolean fallbackUsed;

    @Column(name = "fallback_reason", length = 255)
    private String fallbackReason;

    @Column(length = 10)
    private String confidence;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    @Builder
    public StudentReportAnalysis(Course course,
                                 Student student,
                                 String analysisJson,
                                 String summaryMarkdown,
                                 String source,
                                 Boolean fallbackUsed,
                                 String fallbackReason,
                                 String confidence,
                                 LocalDateTime generatedAt) {
        this.course = course;
        this.student = student;
        this.analysisJson = analysisJson;
        this.summaryMarkdown = summaryMarkdown;
        this.source = source;
        this.fallbackUsed = fallbackUsed != null && fallbackUsed;
        this.fallbackReason = fallbackReason;
        this.confidence = confidence;
        this.generatedAt = generatedAt;
    }

    public void update(String analysisJson,
                       String summaryMarkdown,
                       String source,
                       boolean fallbackUsed,
                       String fallbackReason,
                       String confidence,
                       LocalDateTime generatedAt) {
        this.analysisJson = analysisJson;
        this.summaryMarkdown = summaryMarkdown;
        this.source = source;
        this.fallbackUsed = fallbackUsed;
        this.fallbackReason = fallbackReason;
        this.confidence = confidence;
        this.generatedAt = generatedAt;
    }
}
