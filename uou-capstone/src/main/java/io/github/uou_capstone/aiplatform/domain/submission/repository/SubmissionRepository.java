package io.github.uou_capstone.aiplatform.domain.submission.repository;

import io.github.uou_capstone.aiplatform.domain.assessment.entity.Assessment;
import io.github.uou_capstone.aiplatform.domain.submission.entity.Submission;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {
    // 학생과 평가 ID로 이미 제출 기록이 있는지 확인
    boolean existsByStudentAndAssessment(Student student, Assessment assessment);

    // 특정 평가 ID에 해당하는 모든 제출 기록을 찾는 메서드(선생님)
    List<Submission> findByAssessmentId(Long assessmentId);

    @Query("""
            SELECT s FROM Submission s
            JOIN FETCH s.assessment a
            WHERE a.course.id = :courseId AND s.student.id = :studentId
            ORDER BY s.createdAt DESC
            """)
    List<Submission> findByCourseIdAndStudentIdWithAssessment(@Param("courseId") Long courseId,
                                                              @Param("studentId") Long studentId);

    @Query("""
            SELECT s FROM Submission s
            JOIN FETCH s.assessment a
            WHERE a.course.id = :courseId AND s.student.id IN :studentIds
            """)
    List<Submission> findByCourseIdAndStudentIdsWithAssessment(@Param("courseId") Long courseId,
                                                               @Param("studentIds") Collection<Long> studentIds);

    @Query("""
            SELECT s FROM Submission s
            JOIN FETCH s.assessment a
            JOIN FETCH s.student st
            WHERE a.course.id = :courseId
            """)
    List<Submission> findByCourseIdWithAssessmentAndStudent(@Param("courseId") Long courseId);
}
