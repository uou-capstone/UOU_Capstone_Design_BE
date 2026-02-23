package io.github.uou_capstone.aiplatform.domain.exam.repository;

import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamStatus;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExamSessionRepository extends JpaRepository<ExamSession, Long> {
    
    List<ExamSession> findByLecture(Lecture lecture);
    
    List<ExamSession> findByLectureAndExamType(Lecture lecture, ExamType examType);
    
    List<ExamSession> findByStatus(ExamStatus status);
    
    @Query("SELECT es FROM ExamSession es WHERE es.lecture.id = :lectureId AND es.examType = :examType")
    List<ExamSession> findByLectureIdAndExamType(@Param("lectureId") Long lectureId, @Param("examType") ExamType examType);
}
