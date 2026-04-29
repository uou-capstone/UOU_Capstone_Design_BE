package io.github.uou_capstone.aiplatform.domain.exam.repository;

import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamResult;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ExamResultRepository extends JpaRepository<ExamResult, Long> {

    List<ExamResult> findByExamSession(ExamSession examSession);

    @Query("""
            SELECT er FROM ExamResult er
            JOIN FETCH er.examSession es
            JOIN FETCH es.lecture l
            WHERE l.course.id = :courseId AND er.user.id = :userId
            ORDER BY er.completedAt DESC
            """)
    List<ExamResult> findByCourseIdAndUserIdWithSession(@Param("courseId") Long courseId,
                                                        @Param("userId") Long userId);

    @Query("""
            SELECT er FROM ExamResult er
            JOIN FETCH er.examSession es
            JOIN FETCH es.lecture l
            WHERE l.course.id = :courseId AND er.user.id IN :userIds
            """)
    List<ExamResult> findByCourseIdAndUserIdsWithSession(@Param("courseId") Long courseId,
                                                         @Param("userIds") Collection<Long> userIds);
}
