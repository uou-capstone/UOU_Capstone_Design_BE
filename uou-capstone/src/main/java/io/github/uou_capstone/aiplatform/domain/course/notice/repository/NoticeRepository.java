package io.github.uou_capstone.aiplatform.domain.course.notice.repository;

import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.notice.entity.Notice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NoticeRepository extends JpaRepository<Notice, Long> {

    Page<Notice> findByCourse(Course course, Pageable pageable);

    Optional<Notice> findByIdAndCourse(Long id, Course course);

    List<Notice> findTop5ByCourseOrderByCreatedAtDesc(Course course);
}
