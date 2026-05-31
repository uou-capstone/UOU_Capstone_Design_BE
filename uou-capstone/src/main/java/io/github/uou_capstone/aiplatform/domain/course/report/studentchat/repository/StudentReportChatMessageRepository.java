package io.github.uou_capstone.aiplatform.domain.course.report.studentchat.repository;

import io.github.uou_capstone.aiplatform.domain.course.report.studentchat.entity.StudentReportChatMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudentReportChatMessageRepository extends JpaRepository<StudentReportChatMessage, Long> {

    @EntityGraph(attributePaths = {"chatSession"})
    Page<StudentReportChatMessage> findByChatSession_Course_IdAndChatSession_Student_Id(
            Long courseId, Long studentId, Pageable pageable);

    @EntityGraph(attributePaths = {"chatSession"})
    Page<StudentReportChatMessage> findByChatSession_Course_IdAndChatSession_Student_IdAndChatSession_Id(
            Long courseId, Long studentId, Long sessionId, Pageable pageable);
}
