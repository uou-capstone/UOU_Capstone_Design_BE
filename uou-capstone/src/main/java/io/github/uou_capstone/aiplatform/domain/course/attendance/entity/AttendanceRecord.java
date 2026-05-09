package io.github.uou_capstone.aiplatform.domain.course.attendance.entity;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 학생별 출석 기록. (session, student) 유니크.
 * markedBy NOT NULL — 회차 생성 시 createdBy 교사로 채워짐. 이후 PUT /records 에서 갱신 가능.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "attendance_records",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_ar_session_student",
                        columnNames = {"attendance_session_id", "student_id"})
        })
public class AttendanceRecord extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "attendance_record_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attendance_session_id", nullable = false)
    private AttendanceSession session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private AttendanceStatus status;

    @Column(name = "marked_at")
    private LocalDateTime markedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "marked_by_teacher_id", nullable = false)
    private Teacher markedBy;

    @Column(name = "note", length = 255)
    private String note;

    @Builder
    public AttendanceRecord(AttendanceSession session,
                            Student student,
                            AttendanceStatus status,
                            LocalDateTime markedAt,
                            Teacher markedBy,
                            String note) {
        this.session = session;
        this.student = student;
        this.status = status;
        this.markedAt = markedAt;
        this.markedBy = markedBy;
        this.note = note;
    }

    public void update(AttendanceStatus status, String note, Teacher markedBy, LocalDateTime markedAt) {
        if (status != null) this.status = status;
        // note 는 null 도 의미 있음 (지움) — 단, 호출부에서 명시적으로 null 을 의미할 때만
        this.note = note;
        if (markedBy != null) this.markedBy = markedBy;
        if (markedAt != null) this.markedAt = markedAt;
    }
}
