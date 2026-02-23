package io.github.uou_capstone.aiplatform.domain.exam.repository;

import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamProfile;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ExamProfileRepository extends JpaRepository<ExamProfile, Long> {
    
    Optional<ExamProfile> findByUserAndLecture(User user, Lecture lecture);
    
    @Query("SELECT ep FROM ExamProfile ep WHERE ep.user.id = :userId AND ep.lecture.id = :lectureId")
    Optional<ExamProfile> findByUserIdAndLectureId(@Param("userId") Long userId, @Param("lectureId") Long lectureId);
    
    Optional<ExamProfile> findByContentHash(String contentHash);
}
