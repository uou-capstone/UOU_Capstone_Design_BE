package io.github.uou_capstone.aiplatform.domain.course.attendance.entity;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 출석 회차. 강의(lecture) 매핑 또는 독립 회차 (lecture null) 둘 다 허용.
 *
 * <p>회차 생성 시 ACTIVE 수강생 전체에 대해 ABSENT record 가 자동 생성됨 (서비스 레이어).
 * <p>lecture 가 삭제되면 attendance_sessions.lecture_id 는 NULL 로 떨어지고 세션 자체는 보존됨
 * (DDL 의 ON DELETE SET NULL).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "attendance_sessions",
        indexes = {
                @Index(name = "idx_as_course_date", columnList = "course_id, session_date DESC")
        })
public class AttendanceSession extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "attendance_session_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lecture_id")
    private Lecture lecture;

    @Column(name = "title", length = 100, nullable = false)
    private String title;

    @Column(name = "session_date", nullable = false)
    private LocalDate sessionDate;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_teacher_id", nullable = false)
    private Teacher createdBy;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AttendanceRecord> records = new ArrayList<>();

    @Builder
    public AttendanceSession(Course course,
                             Lecture lecture,
                             String title,
                             LocalDate sessionDate,
                             LocalTime startTime,
                             LocalTime endTime,
                             Teacher createdBy) {
        this.course = course;
        this.lecture = lecture;
        this.title = title;
        this.sessionDate = sessionDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.createdBy = createdBy;
    }

    public void update(String title,
                       LocalDate sessionDate,
                       LocalTime startTime,
                       LocalTime endTime,
                       Lecture lecture) {
        if (title != null && !title.isBlank()) this.title = title;
        if (sessionDate != null) this.sessionDate = sessionDate;
        if (startTime != null) this.startTime = startTime;
        if (endTime != null) this.endTime = endTime;
        // lecture 는 null 로 명시 변경도 허용 (독립 회차로 전환). 호출부에서 필요 시 분기.
        this.lecture = lecture;
    }
}
