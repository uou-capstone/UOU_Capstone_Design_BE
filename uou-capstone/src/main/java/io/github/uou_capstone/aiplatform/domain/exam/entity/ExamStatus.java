package io.github.uou_capstone.aiplatform.domain.exam.entity;

/**
 * 시험 생성 세션 상태
 */
public enum ExamStatus {
    GENERATING,  // 생성 중
    READY,       // 준비 완료
    COMPLETED,   // 완료
    FAILED       // 실패
}
