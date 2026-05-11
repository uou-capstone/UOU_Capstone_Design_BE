package io.github.uou_capstone.aiplatform.domain.course.report.criteria.repository;

import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.entity.CourseReportCriterion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CourseReportCriterionRepository extends JpaRepository<CourseReportCriterion, Long> {

    List<CourseReportCriterion> findByCourseOrderByIdAsc(Course course);

    Optional<CourseReportCriterion> findByIdAndCourse(Long id, Course course);
}
