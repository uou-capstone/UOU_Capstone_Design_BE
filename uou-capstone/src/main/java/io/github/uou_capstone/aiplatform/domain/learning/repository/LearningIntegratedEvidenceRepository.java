package io.github.uou_capstone.aiplatform.domain.learning.repository;

import io.github.uou_capstone.aiplatform.domain.learning.entity.LearningIntegratedEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LearningIntegratedEvidenceRepository extends JpaRepository<LearningIntegratedEvidence, Long> {

    @Query("""
            SELECT e
            FROM LearningIntegratedEvidence e
            WHERE e.lecture.course.id = :courseId
              AND e.user.id = :userId
            ORDER BY e.createdAt DESC, e.id DESC
            """)
    List<LearningIntegratedEvidence> findByCourseIdAndUserIdOrderByRecent(@Param("courseId") Long courseId,
                                                                          @Param("userId") Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM LearningIntegratedEvidence e WHERE e.lecture.course.id = :courseId")
    void deleteByLectureCourseId(@Param("courseId") Long courseId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM LearningIntegratedEvidence e WHERE e.lecture.id = :lectureId")
    void deleteByLectureId(@Param("lectureId") Long lectureId);
}
