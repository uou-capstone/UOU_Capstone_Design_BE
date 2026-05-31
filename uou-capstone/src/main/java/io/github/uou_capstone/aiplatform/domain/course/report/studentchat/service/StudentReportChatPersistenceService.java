package io.github.uou_capstone.aiplatform.domain.course.report.studentchat.service;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.common.web.PageableSupport;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.entity.Enrollment;
import io.github.uou_capstone.aiplatform.domain.course.report.studentchat.dto.StudentReportChatHistoryItem;
import io.github.uou_capstone.aiplatform.domain.course.report.studentchat.entity.StudentReportChatMessage;
import io.github.uou_capstone.aiplatform.domain.course.report.studentchat.entity.StudentReportChatMessageRole;
import io.github.uou_capstone.aiplatform.domain.course.report.studentchat.entity.StudentReportChatSession;
import io.github.uou_capstone.aiplatform.domain.course.report.studentchat.repository.StudentReportChatMessageRepository;
import io.github.uou_capstone.aiplatform.domain.course.report.studentchat.repository.StudentReportChatSessionRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseAccessService;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class StudentReportChatPersistenceService {

    private static final Set<String> HISTORY_SORT_WHITELIST = Set.of("createdAt", "id");
    private static final Sort HISTORY_DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "createdAt")
            .and(Sort.by(Sort.Direction.ASC, "id"));

    private final CourseAccessService courseAccessService;
    private final EnrollmentRepository enrollmentRepository;
    private final StudentReportChatSessionRepository sessionRepository;
    private final StudentReportChatMessageRepository messageRepository;

    @Transactional
    public StudentReportChatSession getOrCreateSession(Long courseId, Long studentId, Long sessionId) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        Enrollment enrollment = enrollmentRepository.findByCourseIdAndStudentIdWithUser(courseId, studentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        Student student = enrollment.getStudent();
        if (sessionId != null) {
            return sessionRepository.findByIdAndCourse_IdAndStudent_Id(sessionId, courseId, studentId)
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));
        }
        StudentReportChatSession session = StudentReportChatSession.builder()
                .course(course)
                .student(student)
                .build();
        session.touch(LocalDateTime.now());
        return sessionRepository.save(session);
    }

    @Transactional
    public void saveUserMessage(Long sessionId, String content) {
        saveMessage(sessionId, StudentReportChatMessageRole.USER, content);
    }

    @Transactional
    public void saveAssistantMessage(Long sessionId, String content) {
        saveMessage(sessionId, StudentReportChatMessageRole.ASSISTANT, content);
    }

    @Transactional(readOnly = true)
    public PageResponse<StudentReportChatHistoryItem> getHistory(Long courseId,
                                                                 Long studentId,
                                                                 Long sessionId,
                                                                 Pageable rawPageable) {
        courseAccessService.loadCourseAsTeacher(courseId);
        enrollmentRepository.findByCourseIdAndStudentIdWithUser(courseId, studentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        if (sessionId != null) {
            sessionRepository.findByIdAndCourse_IdAndStudent_Id(sessionId, courseId, studentId)
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));
        }

        Pageable pageable = PageableSupport.validate(rawPageable, HISTORY_SORT_WHITELIST, HISTORY_DEFAULT_SORT);
        Page<StudentReportChatMessage> page = sessionId == null
                ? messageRepository.findByChatSession_Course_IdAndChatSession_Student_Id(courseId, studentId, pageable)
                : messageRepository.findByChatSession_Course_IdAndChatSession_Student_IdAndChatSession_Id(
                        courseId, studentId, sessionId, pageable);
        return PageResponse.of(page.map(StudentReportChatHistoryItem::new));
    }

    private void saveMessage(Long sessionId, StudentReportChatMessageRole role, String content) {
        if (!StringUtils.hasText(content)) {
            return;
        }
        StudentReportChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));
        session.touch(LocalDateTime.now());
        messageRepository.save(StudentReportChatMessage.builder()
                .chatSession(session)
                .role(role)
                .content(content.trim())
                .build());
    }
}
