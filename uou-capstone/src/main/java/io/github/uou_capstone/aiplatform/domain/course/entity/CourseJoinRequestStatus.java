package io.github.uou_capstone.aiplatform.domain.course.entity;

public enum CourseJoinRequestStatus {
    PENDING,    // 학생이 가입을 요청하고 교사 처리 대기
    APPROVED,   // 교사 승인 완료 (Enrollment 생성됨)
    REJECTED,   // 교사가 거절 (학생 재요청 가능)
    BLOCKED     // 교사가 차단 (학생 재요청 불가)
}
