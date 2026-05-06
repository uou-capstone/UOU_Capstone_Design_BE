package io.github.uou_capstone.aiplatform.domain.course.entity;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.assessment.entity.Assessment;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "courses")
public class Course extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "course_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id", nullable = false)
    private Teacher teacher;


    @Column(nullable = false, length = 255)
    private String title;

    // 긴 강의실 설명을 허용하기 위해 MySQL LONGTEXT로 명시.
    // 주의: ddl-auto:update는 신규 테이블에만 LONGTEXT를 적용하며, 기존 운영 DB의 컬럼 타입은
    // 변경하지 않는다. 기 배포 환경에서는 별도 ALTER TABLE 적용이 필요하다 (DEV_NOTES 참고).
    @Column(columnDefinition = "LONGTEXT")
    private String description;

    @Column(name = "invitation_code", nullable = false, unique = true)
    private String invitationCode; // 인증코드 추가

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<Lecture> lectures = new HashSet<>();

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<Assessment> assessments = new HashSet<>();

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<Enrollment> enrollments = new HashSet<>();

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<CourseJoinRequest> joinRequests = new HashSet<>();

    @Builder
    public Course(Teacher teacher, String title, String description, String invitationCode) {
        this.teacher = teacher;
        this.title = title;
        this.description = description;
        this.invitationCode = invitationCode;
    }

    public void update(String title, String description) {
        if (title != null && !title.isBlank()) {
            this.title = title;
        }
        if (description != null) {
            this.description = description;
        }
    }
}
