package io.github.uou_capstone.aiplatform.domain.notification.entity;

/**
 * 알림 타입. 도메인 별로 발행 시 추가한다.
 *
 * - COURSE_JOIN_*: 강의실 가입 요청 처리 결과(승인/거절/차단)
 * - COURSE_MEMBER_*: 강의실 멤버십 변경(교사가 학생을 직접 제거/차단)
 */
public enum NotificationType {
    COURSE_JOIN_APPROVED,
    COURSE_JOIN_REJECTED,
    COURSE_JOIN_BLOCKED,
    COURSE_MEMBER_REMOVED,
    COURSE_MEMBER_BLOCKED
}
