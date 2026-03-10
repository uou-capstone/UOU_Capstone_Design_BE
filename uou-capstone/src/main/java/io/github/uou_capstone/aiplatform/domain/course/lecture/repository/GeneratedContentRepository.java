package io.github.uou_capstone.aiplatform.domain.course.lecture.repository;

import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.GeneratedContent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GeneratedContentRepository extends JpaRepository<GeneratedContent, Long> {

    /** 강의실 삭제 시: 해당 강의실 강의의 GeneratedContent에서 session 참조 해제 (GenerationSession 선삭제를 위함) */
    @Modifying
    @Query("UPDATE GeneratedContent gc SET gc.session = null WHERE gc.session IS NOT NULL AND gc.session.lecture.course.id = :courseId")
    void clearSessionByCourseId(@Param("courseId") Long courseId);

    /** 강의 삭제 시: 해당 강의의 GeneratedContent에서 session 참조 해제 */
    @Modifying
    @Query("UPDATE GeneratedContent gc SET gc.session = null WHERE gc.lecture.id = :lectureId AND gc.session IS NOT NULL")
    void clearSessionByLectureId(@Param("lectureId") Long lectureId);

    /**
     * 특정 강의 ID에 해당하는 모든 생성된 콘텐츠를 조회합니다.
     * @param lectureId 강의 ID
     * @return 해당 강의에 속한 GeneratedContent 목록
     */
    // Lecture ID로 모든 GeneratedContent를 찾는 메서드 추가
    List<GeneratedContent> findByLectureId(Long lectureId);

    Optional<GeneratedContent> findByAiQuestionId(String aiQuestionId);
}
