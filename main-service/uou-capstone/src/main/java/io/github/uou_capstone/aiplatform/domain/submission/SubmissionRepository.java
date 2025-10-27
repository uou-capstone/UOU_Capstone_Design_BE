package io.github.uou_capstone.aiplatform.domain.submission;

import io.github.uou_capstone.aiplatform.domain.assessment.Assessment;
import io.github.uou_capstone.aiplatform.domain.user.Student;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {
    // 학생과 평가 ID로 이미 제출 기록이 있는지 확인
    boolean existsByStudentAndAssessment(Student student, Assessment assessment);
}
