package io.github.uou_capstone.aiplatform.domain.course.lecture.repository;

import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LectureRepository extends JpaRepository<Lecture, Long> {

    /**
     * Lecture + Course + Teacher 를 단일 JOIN FETCH 쿼리로 조회.
     * lecture.getCourse(), course.getTeacher() 접근 시 추가 SELECT 발생을 방지.
     */
    @Query("SELECT l FROM Lecture l JOIN FETCH l.course c JOIN FETCH c.teacher WHERE l.id = :id")
    Optional<Lecture> findByIdWithCourse(@Param("id") Long id);

    List<Lecture> findByCourseIdOrderByWeekNumberAscIdAsc(Long courseId);

    long countByCourseId(Long courseId);
}
