package io.github.uou_capstone.aiplatform.domain.learning.repository;

import io.github.uou_capstone.aiplatform.domain.learning.entity.LearningChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LearningChatMessageRepository extends JpaRepository<LearningChatMessage, Long> {

    List<LearningChatMessage> findByChatSessionIdOrderByCreatedAtAscIdAsc(Long chatSessionId);

    @Query("""
            SELECT m.pageNumber, COUNT(m)
            FROM LearningChatMessage m
            WHERE m.chatSession.lecture.course.id = :courseId
              AND m.chatSession.user.id = :userId
              AND m.pageNumber IS NOT NULL
            GROUP BY m.pageNumber
            ORDER BY m.pageNumber ASC
            """)
    List<Object[]> countPagesByCourseAndUser(@Param("courseId") Long courseId,
                                             @Param("userId") Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM LearningChatMessage m WHERE m.chatSession.lecture.course.id = :courseId")
    void deleteByLectureCourseId(@Param("courseId") Long courseId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM LearningChatMessage m WHERE m.chatSession.lecture.id = :lectureId")
    void deleteByLectureId(@Param("lectureId") Long lectureId);
}
