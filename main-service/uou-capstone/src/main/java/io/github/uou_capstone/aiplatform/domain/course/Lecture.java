package io.github.uou_capstone.aiplatform.domain.course;


import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "lectures")
public class Lecture extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "lecture_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private int weekNumber;

    @Lob
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_generated_status", nullable = false)
    private AiGeneratedStatus aiGeneratedStatus;

    @Builder
    public Lecture(Course course, String title, int weekNumber, String description) {
        this.course = course;
        this.title = title;
        this.weekNumber = weekNumber;
        this.description = description;
        this.aiGeneratedStatus = AiGeneratedStatus.PENDING; // 생성 시 기본 상태는 PENDING
    }
}