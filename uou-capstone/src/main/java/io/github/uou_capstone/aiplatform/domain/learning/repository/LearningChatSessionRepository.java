package io.github.uou_capstone.aiplatform.domain.learning.repository;

import io.github.uou_capstone.aiplatform.domain.learning.entity.LearningChatSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LearningChatSessionRepository extends JpaRepository<LearningChatSession, Long> {

    @EntityGraph(attributePaths = {"lecture", "lecture.course", "user"})
    Optional<LearningChatSession> findByIdAndUserIdAndLectureId(Long id, Long userId, Long lectureId);

    @EntityGraph(attributePaths = {"lecture", "user"})
    Optional<LearningChatSession> findByIdAndUserId(Long id, Long userId);

    Page<LearningChatSession> findByUserIdAndLectureId(Long userId, Long lectureId, Pageable pageable);

    @Query("""
            SELECT DISTINCT s.lecture.id
            FROM LearningChatSession s
            WHERE s.lecture.course.id = :courseId AND s.user.id = :userId
            """)
    List<Long> findDistinctLectureIdsByCourseAndUser(@Param("courseId") Long courseId,
                                                     @Param("userId") Long userId);

    @Query("""
            SELECT DISTINCT s.lecture.id, s.user.id
            FROM LearningChatSession s
            WHERE s.lecture.course.id = :courseId
            """)
    List<Object[]> findLectureUserPairsByCourse(@Param("courseId") Long courseId);
}
