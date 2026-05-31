package io.github.uou_capstone.aiplatform.domain.learning.repository;

import io.github.uou_capstone.aiplatform.domain.learning.entity.LearningChatSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LearningChatSessionRepository extends JpaRepository<LearningChatSession, Long> {

    @EntityGraph(attributePaths = {"lecture", "lecture.course", "user"})
    Optional<LearningChatSession> findByIdAndUserIdAndLectureId(Long id, Long userId, Long lectureId);

    @EntityGraph(attributePaths = {"lecture", "user"})
    Optional<LearningChatSession> findByIdAndUserId(Long id, Long userId);

    Page<LearningChatSession> findByUserIdAndLectureId(Long userId, Long lectureId, Pageable pageable);
}
