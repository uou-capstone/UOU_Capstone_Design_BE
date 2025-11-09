package io.github.uou_capstone.aiplatform.domain.course.lecture;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.user.Student;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "generated_content")
public class GeneratedContent extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "generated_content_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lecture_id", nullable = false)
    private Lecture lecture; // 어떤 강의에 속하는지

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false)
    private ContentType contentType; // 콘텐츠 종류

    @Lob // json
    @Column(name = "content_data", nullable = false)
    private String contentData;

    @Lob //json
    @Column(name = "material_references")
    private String materialReferences;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private Student student; // 학생이 생성했을 경우 (nullable)

    @Builder
    public GeneratedContent(Lecture lecture, ContentType contentType, String contentData,
                            String materialReferences, Student student) {
        this.lecture = lecture;
        this.contentType = contentType;
        this.contentData = contentData;
        this.materialReferences = materialReferences;
        this.student = student;
    }
}