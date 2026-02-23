package io.github.uou_capstone.aiplatform.domain.course.repository;

import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CourseRepository extends JpaRepository<Course, Long> {
    List<Course> findByTeacher(Teacher teacher);
    List<Course> findByTeacherOrderByCreatedAtDesc(Teacher teacher); // 최신순 정렬 추가
    Optional<Course> findByInvitationCode(String invitationCode);
    boolean existsByInvitationCode(String invitationCode);
}
