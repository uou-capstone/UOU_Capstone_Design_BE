package io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.repository;

import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.entity.StudentReportAnalysis;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StudentReportAnalysisRepository extends JpaRepository<StudentReportAnalysis, Long> {
    Optional<StudentReportAnalysis> findByCourseAndStudent(Course course, Student student);
}
