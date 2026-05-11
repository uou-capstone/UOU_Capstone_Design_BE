package io.github.uou_capstone.aiplatform.domain.course.report.criteria.entity;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "course_report_criteria",
        indexes = {@Index(name = "idx_crc_course", columnList = "course_id")})
public class CourseReportCriterion extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "criterion_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(length = 500)
    private String description;

    /** 0–100 — Service 레이어에서 검증. */
    @Column(nullable = false)
    private int weight;

    @Builder
    public CourseReportCriterion(Course course, String label, String description, Integer weight) {
        this.course = course;
        this.label = label;
        this.description = description;
        this.weight = weight == null ? 0 : weight;
    }

    public void update(String label, String description, Integer weight) {
        if (label != null && !label.isBlank()) this.label = label;
        if (description != null) this.description = description;
        if (weight != null) this.weight = weight;
    }
}
