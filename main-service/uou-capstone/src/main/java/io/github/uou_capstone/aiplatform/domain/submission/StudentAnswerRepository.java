package io.github.uou_capstone.aiplatform.domain.submission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StudentAnswerRepository extends JpaRepository<StudentAnswer, Long> {

    List<StudentAnswer> findBySubmissionId(Long submissionId);
}
