package io.github.uou_capstone.aiplatform.domain.material.generation;

import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GenerationSessionRepository extends JpaRepository<GenerationSession, Long> {

    @Modifying
    @Query("DELETE FROM GenerationSession gs WHERE gs.lecture.course.id = :courseId")
    void deleteByLectureCourseId(@Param("courseId") Long courseId);

    @Modifying
    @Query("DELETE FROM GenerationSession gs WHERE gs.lecture.id = :lectureId")
    void deleteByLectureId(@Param("lectureId") Long lectureId);

    List<GenerationSession> findByLecture(Lecture lecture);
    
    List<GenerationSession> findByUser(User user);
    
    List<GenerationSession> findByCurrentPhase(GenerationPhase currentPhase);
    
    @Query("SELECT gs FROM GenerationSession gs WHERE gs.lecture.id = :lectureId ORDER BY gs.createdAt DESC")
    List<GenerationSession> findByLectureIdOrderByCreatedAtDesc(@Param("lectureId") Long lectureId);
    
    @Query("SELECT gs FROM GenerationSession gs WHERE gs.lecture.id = :lectureId AND gs.currentPhase = :phase")
    Optional<GenerationSession> findByLectureIdAndPhase(@Param("lectureId") Long lectureId, @Param("phase") GenerationPhase phase);
}
