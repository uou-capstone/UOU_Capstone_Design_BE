package io.github.uou_capstone.aiplatform.domain.learning.repository;

import io.github.uou_capstone.aiplatform.domain.learning.entity.LearningSessionEvidence;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LearningSessionEvidenceRepository extends JpaRepository<LearningSessionEvidence, Long> {

    boolean existsByEvidenceId(String evidenceId);

    @EntityGraph(attributePaths = {"course", "lecture", "material", "student", "session"})
    List<LearningSessionEvidence> findTop50ByCourseIdAndStudentIdOrderByOccurredAtDescIdDesc(Long courseId,
                                                                                              Long studentId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM LearningSessionEvidence e WHERE e.course.id = :courseId")
    void deleteByCourseId(@Param("courseId") Long courseId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM LearningSessionEvidence e WHERE e.lecture.id = :lectureId")
    void deleteByLectureId(@Param("lectureId") Long lectureId);
}
