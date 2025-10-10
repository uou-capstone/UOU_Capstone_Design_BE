package io.github.uou_capstone.aiplatform.domain.assessment;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.course.Course;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "assessments")
public class Assessment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "assessment_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(nullable = false)
    private String title;

    @Column(name = "due_date")
    private LocalDateTime dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "assessment_type", nullable = false)
    private AssessmentType type; // 월말 평가 느낌, 그냥 퀴즈 느낌

    @Builder
    public Assessment(Course course, String title, AssessmentType type,  LocalDateTime dueDate) {
        this.course = course;
        this.title = title;
        this.type = type;
        this.dueDate = dueDate;
    }
}