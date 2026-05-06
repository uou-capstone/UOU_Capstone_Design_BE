package io.github.uou_capstone.aiplatform.domain.course.repository;

import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.entity.CourseJoinRequest;
import io.github.uou_capstone.aiplatform.domain.course.entity.CourseJoinRequestStatus;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CourseJoinRequestRepository extends JpaRepository<CourseJoinRequest, Long> {

    boolean existsByStudentAndCourseAndStatus(Student student,
                                              Course course,
                                              CourseJoinRequestStatus status);

    boolean existsByStudentAndCourseAndStatusIn(Student student,
                                                Course course,
                                                Collection<CourseJoinRequestStatus> statuses);

    List<CourseJoinRequest> findByStudentAndCourseAndStatus(Student student,
                                                            Course course,
                                                            CourseJoinRequestStatus status);

    boolean existsByStudentIdAndCourseIdAndStatusIn(Long studentId,
                                                    Long courseId,
                                                    Collection<CourseJoinRequestStatus> statuses);

    @Query(value = """
            SELECT r FROM CourseJoinRequest r
            JOIN FETCH r.student s
            JOIN FETCH s.user
            WHERE r.course.id = :courseId AND r.status = :status
            """,
           countQuery = """
            SELECT COUNT(r) FROM CourseJoinRequest r
            WHERE r.course.id = :courseId AND r.status = :status
            """)
    Page<CourseJoinRequest> findByCourseIdAndStatusWithStudent(
            @Param("courseId") Long courseId,
            @Param("status") CourseJoinRequestStatus status,
            Pageable pageable);

    @Query(value = """
            SELECT r FROM CourseJoinRequest r
            JOIN FETCH r.course c
            WHERE r.student.id = :studentId
            """,
           countQuery = """
            SELECT COUNT(r) FROM CourseJoinRequest r
            WHERE r.student.id = :studentId
            """)
    Page<CourseJoinRequest> findByStudentIdWithCourse(
            @Param("studentId") Long studentId,
            Pageable pageable);

    Optional<CourseJoinRequest> findByIdAndCourseId(Long id, Long courseId);
}
