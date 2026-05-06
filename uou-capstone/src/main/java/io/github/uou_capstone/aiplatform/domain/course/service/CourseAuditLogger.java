package io.github.uou_capstone.aiplatform.domain.course.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 강의실 멤버십/가입 요청 처리 흐름의 운영 추적용 감사 로그.
 *
 * - 별도 테이블에 저장하지 않고 SLF4J 구조화 로그(MDC 가능)로 남긴다.
 * - prod 에서는 io.github.uou_capstone 로거가 INFO 이상이라 그대로 적재된다.
 * - 분쟁/문의 대응 시 grep 으로 추적할 수 있도록 키=값 형태로 통일.
 */
@Slf4j
@Component
public class CourseAuditLogger {

    private static final String EVT_STUDENT_REMOVED = "STUDENT_REMOVED";
    private static final String EVT_STUDENT_BLOCKED = "STUDENT_BLOCKED";
    private static final String EVT_STUDENT_REJOINED = "STUDENT_REJOINED";
    private static final String EVT_JOIN_APPROVED = "JOIN_APPROVED";
    private static final String EVT_JOIN_REJECTED = "JOIN_REJECTED";
    private static final String EVT_JOIN_BLOCKED = "JOIN_BLOCKED";

    public void studentRemoved(Long courseId, Long studentId, Long teacherUserId) {
        log.info("course-audit event={} courseId={} studentId={} actorUserId={}",
                EVT_STUDENT_REMOVED, courseId, studentId, teacherUserId);
    }

    public void studentBlocked(Long courseId, Long studentId, Long teacherUserId) {
        log.info("course-audit event={} courseId={} studentId={} actorUserId={}",
                EVT_STUDENT_BLOCKED, courseId, studentId, teacherUserId);
    }

    public void studentRejoined(Long courseId, Long studentId) {
        log.info("course-audit event={} courseId={} studentId={}",
                EVT_STUDENT_REJOINED, courseId, studentId);
    }

    public void joinApproved(Long courseId, Long studentId, Long teacherUserId) {
        log.info("course-audit event={} courseId={} studentId={} actorUserId={}",
                EVT_JOIN_APPROVED, courseId, studentId, teacherUserId);
    }

    public void joinRejected(Long courseId, Long studentId, Long teacherUserId) {
        log.info("course-audit event={} courseId={} studentId={} actorUserId={}",
                EVT_JOIN_REJECTED, courseId, studentId, teacherUserId);
    }

    public void joinBlocked(Long courseId, Long studentId, Long teacherUserId) {
        log.info("course-audit event={} courseId={} studentId={} actorUserId={}",
                EVT_JOIN_BLOCKED, courseId, studentId, teacherUserId);
    }
}
