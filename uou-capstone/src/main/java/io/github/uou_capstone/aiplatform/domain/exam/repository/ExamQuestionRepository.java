package io.github.uou_capstone.aiplatform.domain.exam.repository;

import io.github.uou_capstone.aiplatform.domain.assessment.entity.Assessment;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamQuestion;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExamQuestionRepository extends JpaRepository<ExamQuestion, Long> {
    
    List<ExamQuestion> findByAssessment(Assessment assessment);
    
    List<ExamQuestion> findByExamSession(ExamSession examSession);

    boolean existsByExamSession(ExamSession examSession);

    List<ExamQuestion> findByExamType(ExamType examType);
    
    @Query("SELECT eq FROM ExamQuestion eq WHERE eq.assessment.id = :assessmentId ORDER BY eq.questionOrder")
    List<ExamQuestion> findByAssessmentIdOrderByQuestionOrder(@Param("assessmentId") Long assessmentId);
    
    @Query("SELECT eq FROM ExamQuestion eq WHERE eq.examSession.id = :examSessionId ORDER BY eq.questionOrder")
    List<ExamQuestion> findByExamSessionIdOrderByQuestionOrder(@Param("examSessionId") Long examSessionId);
}
