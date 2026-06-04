package io.github.uou_capstone.aiplatform.domain.learning.repository;

import io.github.uou_capstone.aiplatform.domain.learning.entity.LearningSessionEvidence;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LearningSessionEvidenceRepository extends JpaRepository<LearningSessionEvidence, Long> {

    boolean existsByEvidenceId(String evidenceId);

    @EntityGraph(attributePaths = {"course", "lecture", "material", "student", "session"})
    List<LearningSessionEvidence> findTop50ByCourseIdAndStudentIdOrderByOccurredAtDescIdDesc(Long courseId,
                                                                                              Long studentId);
}
