package io.github.uou_capstone.aiplatform.domain.course.lecture;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GeneratedContentRepository extends JpaRepository<GeneratedContent, Long> {

    /**
     * 특정 강의 ID에 해당하는 모든 생성된 콘텐츠를 조회합니다.
     * @param lectureId 강의 ID
     * @return 해당 강의에 속한 GeneratedContent 목록
     */
    // Lecture ID로 모든 GeneratedContent를 찾는 메서드 추가
    List<GeneratedContent> findByLectureId(Long lectureId);

    Optional<GeneratedContent> findByAiQuestionId(String aiQuestionId);
}