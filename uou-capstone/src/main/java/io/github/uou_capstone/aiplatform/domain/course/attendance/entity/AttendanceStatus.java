package io.github.uou_capstone.aiplatform.domain.course.attendance.entity;

/**
 * 출석 상태.
 * - PRESENT: 출석
 * - LATE: 지각
 * - ABSENT: 결석 (세션 생성 시 모든 ACTIVE 수강생에 대해 기본값으로 자동 생성)
 * - EXCUSED: 사유 결석
 */
public enum AttendanceStatus {
    PRESENT,
    LATE,
    ABSENT,
    EXCUSED
}
