package io.github.uou_capstone.aiplatform.domain.course.discussion.repository;

import io.github.uou_capstone.aiplatform.domain.course.discussion.entity.Discussion;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DiscussionRepository extends JpaRepository<Discussion, Long> {

    Page<Discussion> findByCourse(Course course, Pageable pageable);

    Optional<Discussion> findByIdAndCourse(Long id, Course course);

    /** AI Assistant 컨텍스트용 — 최근 5개 게시글 (pinned 무관, 최신순). */
    List<Discussion> findTop5ByCourseOrderByCreatedAtDesc(Course course);

    /**
     * 상세 조회 시 view_count 증가. 같은 사용자 반복 조회는 디바운스하지 않음 (1차 단순 정책).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Discussion d SET d.viewCount = d.viewCount + 1 WHERE d.id = :id")
    void incrementViewCount(@Param("id") Long id);
}
