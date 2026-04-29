package io.github.uou_capstone.aiplatform.domain.course.repository;

import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.entity.Enrollment;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {
    // 학생과 과목으로 수강 등록 정보가 있는지 확인
    boolean existsByStudentAndCourse(Student student, Course course);

    List<Enrollment> findByStudent(Student student);

    @Query("""
            SELECT e FROM Enrollment e
            JOIN FETCH e.student s
            JOIN FETCH s.user
            WHERE e.course.id = :courseId
            """)
    List<Enrollment> findByCourseIdWithStudentUser(@Param("courseId") Long courseId);

    @Query("""
            SELECT e FROM Enrollment e
            JOIN FETCH e.student s
            JOIN FETCH s.user
            WHERE e.course.id = :courseId AND s.id = :studentId
            """)
    Optional<Enrollment> findByCourseIdAndStudentIdWithUser(@Param("courseId") Long courseId,
                                                            @Param("studentId") Long studentId);
}
