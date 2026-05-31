package io.github.uou_capstone.aiplatform.domain.course.report.studentchat.entity;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "student_report_chat_sessions",
        indexes = {
                @Index(name = "idx_srcs_course_student", columnList = "course_id, student_id"),
                @Index(name = "idx_srcs_last_message", columnList = "last_message_at")
        })
public class StudentReportChatSession extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_chat_session_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @Builder
    public StudentReportChatSession(Course course, Student student) {
        this.course = course;
        this.student = student;
    }

    public void touch(LocalDateTime now) {
        this.lastMessageAt = now;
    }
}
