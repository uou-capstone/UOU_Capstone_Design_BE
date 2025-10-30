package io.github.uou_capstone.aiplatform.domain.material;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.course.lecture.Lecture;
import jakarta.persistence.*;
import lombok.AccessLevel;
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
    private String displayName; // 자료 이름

    @Column(name = "material_type")
    private String materialType;

    @Column(name = "file_path")
    private String filePath;

    @Column
    private String url; // 외부 경로

    @Column(name = "uploaded_by")
    private Long uploadedBy; // User ID
}