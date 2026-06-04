package io.github.uou_capstone.aiplatform.domain.course.report.classroom.repository;

import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.report.classroom.entity.ClassroomReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClassroomReportRepository extends JpaRepository<ClassroomReport, Long> {
    Optional<ClassroomReport> findByCourse(Course course);
}
