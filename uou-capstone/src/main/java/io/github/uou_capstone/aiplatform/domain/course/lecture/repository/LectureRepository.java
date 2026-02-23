package io.github.uou_capstone.aiplatform.domain.course.lecture.repository;

import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LectureRepository extends JpaRepository<Lecture, Long> {
}
