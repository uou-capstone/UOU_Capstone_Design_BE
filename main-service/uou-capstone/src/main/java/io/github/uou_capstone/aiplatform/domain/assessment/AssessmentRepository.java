package io.github.uou_capstone.aiplatform.domain.assessment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AssessmentRepository extends JpaRepository<Assessment, Long> {

    // 특정 과목 ID로 모든 평가를 찾는 메서드 추가
    List<Assessment> findByCourse_Id(Long courseId);

    /**
     * N+1 문제를 해결하기 위해 Fetch Join을 사용.
     * 평가(Assessment)를 조회할 때 연관된 문제(questions)와
     * 그 문제에 속한 선택지(options)까지 한 번의 쿼리로 모두 가져옴
     */
    @Query("SELECT DISTINCT a FROM Assessment a " +
            "LEFT JOIN FETCH a.questions q " +
            "WHERE a.id = :assessmentId")
    Optional<Assessment> findByIdWithQuestions(@Param("assessmentId") Long assessmentId);
}