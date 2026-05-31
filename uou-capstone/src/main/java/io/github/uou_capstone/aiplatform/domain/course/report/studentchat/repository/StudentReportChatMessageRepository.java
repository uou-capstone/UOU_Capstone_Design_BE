package io.github.uou_capstone.aiplatform.domain.course.report.studentchat.repository;

import io.github.uou_capstone.aiplatform.domain.course.report.studentchat.entity.StudentReportChatMessage;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StudentReportChatMessageRepository extends JpaRepository<StudentReportChatMessage, Long> {

    @EntityGraph(attributePaths = {"chatSession"})
    List<StudentReportChatMessage> findByChatSession_Course_IdAndChatSession_Student_IdOrderByCreatedAtAscIdAsc(
            Long courseId, Long studentId);
}
