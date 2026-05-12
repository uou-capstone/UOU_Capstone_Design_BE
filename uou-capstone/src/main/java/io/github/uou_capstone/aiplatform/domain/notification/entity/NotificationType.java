package io.github.uou_capstone.aiplatform.domain.notification.entity;

/**
 * 알림 타입. 도메인 별로 발행 시 추가한다.
 *
 * 학생/공통 (recipient = 학생):
 * - COURSE_JOIN_*: 강의실 가입 요청 처리 결과(승인/거절/차단)
 * - COURSE_MEMBER_*: 강의실 멤버십 변경(교사가 학생을 직접 제거/차단)
 * - NOTICE_PUBLISHED: 새 공지 게시
 * - NOTICE_COMMENT_REPLIED: 내 공지 댓글에 답글 (학생/교사 양쪽 가능)
 * - DISCUSSION_COMMENT_RECEIVED: 내 토론 게시글/댓글에 댓글 수신
 *
 * 교사용 (recipient = 강의실 담당 교사):
 * - COURSE_JOIN_REQUESTED: 학생이 가입 요청을 생성
 * - DISCUSSION_CREATED: 학생이 토론 게시글 작성
 * - DISCUSSION_COMMENTED: 학생이 토론 댓글 작성 (담당 교사 알림)
 * - NOTICE_COMMENTED: 학생이 공지 댓글 작성 (담당 교사 알림)
 * - ASSESSMENT_SUBMITTED: 학생 과제 제출
 * - EXAM_SUBMITTED: 학생 시험 제출 완료
 * - AI_GENERATION_COMPLETED: AI 자료/시험 비동기 생성 완료 (resourceType=material|exam)
 * - AI_GENERATION_FAILED: AI 자료/시험 비동기 생성 실패 (resourceType=material|exam)
 * - TEACHER_ACTION_CONFIRMED: 교사 본인 작업 확인 (TeacherNotificationPreference.includeSelfActionNotifications=true 시에만)
 *
 * <p>DB 칼럼은 {@code @Enumerated(STRING)} 으로 varchar(40) 매핑이라 enum 추가 시 DDL 변경 불필요
 * (V4__teacher_notification_prefs_and_type_widen.sql 에서 기존 ENUM 컬럼을 VARCHAR(40)으로 정렬 완료).
 */
public enum NotificationType {
    COURSE_JOIN_APPROVED,
    COURSE_JOIN_REJECTED,
    COURSE_JOIN_BLOCKED,
    COURSE_MEMBER_REMOVED,
    COURSE_MEMBER_BLOCKED,

    NOTICE_PUBLISHED,
    NOTICE_COMMENT_REPLIED,

    DISCUSSION_COMMENT_RECEIVED,

    // 교사용 신규
    COURSE_JOIN_REQUESTED,
    DISCUSSION_CREATED,
    DISCUSSION_COMMENTED,
    NOTICE_COMMENTED,
    ASSESSMENT_SUBMITTED,
    EXAM_SUBMITTED,
    AI_GENERATION_COMPLETED,
    AI_GENERATION_FAILED,
    TEACHER_ACTION_CONFIRMED
}
