package io.github.uou_capstone.aiplatform.domain.course.report.studentchat.repository;

import io.github.uou_capstone.aiplatform.domain.course.report.studentchat.entity.StudentReportChatSession;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StudentReportChatSessionRepository extends JpaRepository<StudentReportChatSession, Long> {

    @EntityGraph(attributePaths = {"course", "student", "student.user"})
    Optional<StudentReportChatSession> findByIdAndCourse_IdAndStudent_Id(Long id, Long courseId, Long studentId);
}
