package io.github.uou_capstone.aiplatform.domain.course.repository;

import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.entity.Enrollment;
import io.github.uou_capstone.aiplatform.domain.course.entity.EnrollmentStatus;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {
    // 학생과 과목으로 수강 등록 정보가 있는지 확인
    boolean existsByStudentAndCourse(Student student, Course course);

    /**
     * (student, course, status) 조합 존재 확인. 신규 도메인(notice/discussion/attendance)에서
     * "ACTIVE 수강생만 허용" 권한 체크용.
     */
    boolean existsByStudentAndCourseAndStatus(Student student, Course course, EnrollmentStatus status);

    List<Enrollment> findByStudent(Student student);

    /**
     * 강의실의 특정 status 수강생을 student.user 까지 한 번에 로드 (N+1 방지).
     * 알림 발송 / 출석 자동 생성 / summary 계산에서 student.user 자주 접근하므로 JOIN FETCH 필수.
     */
    @Query("""
            SELECT e FROM Enrollment e
            JOIN FETCH e.student s
            JOIN FETCH s.user
            WHERE e.course = :course AND e.status = :status
            """)
    List<Enrollment> findByCourseAndStatusWithStudentUser(@Param("course") Course course,
                                                          @Param("status") EnrollmentStatus status);

    @Query("""
            SELECT e FROM Enrollment e
            JOIN FETCH e.student s
            JOIN FETCH s.user
            WHERE e.course.id = :courseId
            """)
    List<Enrollment> findByCourseIdWithStudentUser(@Param("courseId") Long courseId);

    @Query(value = """
            SELECT e FROM Enrollment e
            JOIN FETCH e.student s
            JOIN FETCH s.user
            WHERE e.course.id = :courseId
            """,
           countQuery = """
            SELECT COUNT(e) FROM Enrollment e
            WHERE e.course.id = :courseId
            """)
    Page<Enrollment> findByCourseIdWithStudentUser(@Param("courseId") Long courseId, Pageable pageable);

    @Query("""
            SELECT e FROM Enrollment e
            JOIN FETCH e.student s
            JOIN FETCH s.user
            WHERE e.course.id = :courseId AND s.id = :studentId
            """)
    Optional<Enrollment> findByCourseIdAndStudentIdWithUser(@Param("courseId") Long courseId,
                                                            @Param("studentId") Long studentId);
}
