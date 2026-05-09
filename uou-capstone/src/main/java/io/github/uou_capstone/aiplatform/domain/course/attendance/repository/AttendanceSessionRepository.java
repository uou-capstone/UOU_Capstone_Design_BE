package io.github.uou_capstone.aiplatform.domain.course.attendance.repository;

import io.github.uou_capstone.aiplatform.domain.course.attendance.entity.AttendanceSession;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AttendanceSessionRepository extends JpaRepository<AttendanceSession, Long> {

    Page<AttendanceSession> findByCourse(Course course, Pageable pageable);

    /** Summary 매트릭스의 회차 헤더 — 비페이징 전체 (보통 회차 수는 학생 수보다 적음). */
    List<AttendanceSession> findByCourseOrderBySessionDateDesc(Course course);

    Optional<AttendanceSession> findByIdAndCourse(Long id, Course course);
}
