package io.github.uou_capstone.aiplatform.domain.course.lecture;


import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.course.Course;
import io.github.uou_capstone.aiplatform.domain.inquiry.StudentInquiry;
import io.github.uou_capstone.aiplatform.domain.material.Material;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

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

    @OneToMany(mappedBy = "lecture", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<GeneratedContent> generatedContents = new ArrayList<>();

    @OneToMany(mappedBy = "lecture", cascade =  CascadeType.ALL, orphanRemoval = true)
    private List<StudentInquiry> studentInquiries = new ArrayList<>();

    @OneToMany(mappedBy = "lecture", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Material> materials = new ArrayList<>();

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

    public void update(String title, Integer weekNumber, String description) {
        if (title != null && !title.isBlank()) {
            this.title = title;
        }
        if (weekNumber != null) {
            this.weekNumber = weekNumber;
        }
        if (description != null) {
            this.description = description;
        }
    }

    public void updateAiGeneratedStatus(AiGeneratedStatus status) {
        this.aiGeneratedStatus = status;
    }
}