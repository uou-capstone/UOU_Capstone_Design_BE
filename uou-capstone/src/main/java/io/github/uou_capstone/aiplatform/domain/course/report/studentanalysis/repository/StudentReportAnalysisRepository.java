package io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.repository;

import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.entity.StudentReportAnalysis;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StudentReportAnalysisRepository extends JpaRepository<StudentReportAnalysis, Long> {
    Optional<StudentReportAnalysis> findByCourseAndStudent(Course course, Student student);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM StudentReportAnalysis a WHERE a.course.id = :courseId")
    int deleteByCourseId(@Param("courseId") Long courseId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM StudentReportAnalysis a WHERE a.course.id = :courseId AND a.student.id = :studentId")
    int deleteByCourseIdAndStudentId(@Param("courseId") Long courseId, @Param("studentId") Long studentId);
}
