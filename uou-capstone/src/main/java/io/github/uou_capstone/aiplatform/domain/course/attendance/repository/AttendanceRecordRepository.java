package io.github.uou_capstone.aiplatform.domain.course.attendance.repository;

import io.github.uou_capstone.aiplatform.domain.course.attendance.entity.AttendanceRecord;
import io.github.uou_capstone.aiplatform.domain.course.attendance.entity.AttendanceSession;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, Long> {

    List<AttendanceRecord> findBySession(AttendanceSession session);

    /** 학생 본인 출석 요약 — 자신의 강의실 record 를 시간역순으로. */
    @Query("""
            SELECT r FROM AttendanceRecord r
              JOIN FETCH r.session s
             WHERE s.course = :course AND r.student = :student
             ORDER BY s.sessionDate DESC
            """)
    List<AttendanceRecord> findByCourseAndStudentWithSession(@Param("course") Course course,
                                                             @Param("student") Student student);

    /** 매트릭스 — 강의실 전체 record + session JOIN. */
    @Query("""
            SELECT r FROM AttendanceRecord r
              JOIN FETCH r.session s
             WHERE s.course = :course
            """)
    List<AttendanceRecord> findAllByCourseWithSession(@Param("course") Course course);
}
