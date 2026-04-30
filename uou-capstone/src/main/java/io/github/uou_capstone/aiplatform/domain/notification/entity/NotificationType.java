package io.github.uou_capstone.aiplatform.domain.notification.entity;

/**
 * 알림 타입. 도메인 별로 발행 시 추가한다.
 *
 * - COURSE_JOIN_*: 강의실 가입 요청 처리 결과(승인/거절/차단)
 */
public enum NotificationType {
    COURSE_JOIN_APPROVED,
    COURSE_JOIN_REJECTED,
    COURSE_JOIN_BLOCKED
}
