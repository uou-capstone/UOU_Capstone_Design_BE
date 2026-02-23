package io.github.uou_capstone.aiplatform.domain.material.entity;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "material")
public class Material extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "material_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lecture_id", nullable = false)
    private Lecture lecture;

    @Column(name = "display_name")
    private String displayName; // material name

    @Column(name = "material_type")
    private String materialType;

    @Column(name = "file_path")
    private String filePath; // ai-service returned path

    @Column
    private String url; // external link

    @Column(name = "uploaded_by")
    private Long uploadedBy; // User ID

    @Builder
    public Material(Lecture lecture, String displayName, String materialType, String filePath, String url, Long uploadedBy) {
        this.lecture = lecture;
        this.displayName = displayName;
        this.materialType = materialType;
        this.filePath = filePath;
        this.url = url;
        this.uploadedBy = uploadedBy;
    }
}
