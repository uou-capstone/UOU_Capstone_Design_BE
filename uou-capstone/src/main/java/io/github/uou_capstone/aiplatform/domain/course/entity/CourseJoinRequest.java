package io.github.uou_capstone.aiplatform.domain.course.entity;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "course_join_requests",
        indexes = {
                @Index(name = "idx_join_request_course_status", columnList = "course_id, status"),
                @Index(name = "idx_join_request_student_course", columnList = "student_id, course_id")
        })
public class CourseJoinRequest extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "join_request_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CourseJoinRequestStatus status;

    @Builder
    public CourseJoinRequest(Student student, Course course) {
        this.student = student;
        this.course = course;
        this.status = CourseJoinRequestStatus.PENDING;
    }

    public void approve() {
        this.status = CourseJoinRequestStatus.APPROVED;
    }

    public void reject() {
        this.status = CourseJoinRequestStatus.REJECTED;
    }

    public void block() {
        this.status = CourseJoinRequestStatus.BLOCKED;
    }
}
