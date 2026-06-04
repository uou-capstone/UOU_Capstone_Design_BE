package io.github.uou_capstone.aiplatform.domain.course.report.classroom.entity;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 강의실 종합 리포트 — 1 course = 1 row (UPSERT).
 *
 * <p>highlights / risks / coachingPriorities 는 FastAPI 가 배열로 반환하므로 LONGTEXT(JSON 문자열)로 보관.
 * source / fallbackUsed / confidence / fallbackReason 은 FE 가 "AI fallback 표시"용으로 사용.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "classroom_reports",
        uniqueConstraints = @UniqueConstraint(name = "uq_clr_course", columnNames = "course_id"))
public class ClassroomReport extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "classroom_report_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(name = "summary_markdown", columnDefinition = "LONGTEXT")
    private String summaryMarkdown;

    @Column(name = "highlights_json", columnDefinition = "LONGTEXT")
    private String highlightsJson;

    @Column(name = "risks_json", columnDefinition = "LONGTEXT")
    private String risksJson;

    @Column(name = "coaching_priorities_json", columnDefinition = "LONGTEXT")
    private String coachingPrioritiesJson;

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
    public ClassroomReport(Course course,
                           String summaryMarkdown,
                           String highlightsJson,
                           String risksJson,
                           String coachingPrioritiesJson,
                           String source,
                           Boolean fallbackUsed,
                           String fallbackReason,
                           String confidence,
                           LocalDateTime generatedAt) {
        this.course = course;
        this.summaryMarkdown = summaryMarkdown;
        this.highlightsJson = highlightsJson;
        this.risksJson = risksJson;
        this.coachingPrioritiesJson = coachingPrioritiesJson;
        this.source = source;
        this.fallbackUsed = fallbackUsed != null && fallbackUsed;
        this.fallbackReason = fallbackReason;
        this.confidence = confidence;
        this.generatedAt = generatedAt;
    }

    public void update(String summaryMarkdown,
                       String highlightsJson,
                       String risksJson,
                       String coachingPrioritiesJson,
                       String source,
                       boolean fallbackUsed,
                       String fallbackReason,
                       String confidence,
                       LocalDateTime generatedAt) {
        this.summaryMarkdown = summaryMarkdown;
        this.highlightsJson = highlightsJson;
        this.risksJson = risksJson;
        this.coachingPrioritiesJson = coachingPrioritiesJson;
        this.source = source;
        this.fallbackUsed = fallbackUsed;
        this.fallbackReason = fallbackReason;
        this.confidence = confidence;
        this.generatedAt = generatedAt;
    }
}
