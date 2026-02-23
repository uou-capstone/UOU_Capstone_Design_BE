package io.github.uou_capstone.aiplatform.domain.task.entity;

/**
 * 비동기 작업 상태
 */
public enum TaskStatus {
    QUEUED,      // 대기 중
    PROCESSING,  // 처리 중
    COMPLETED,   // 완료
    FAILED       // 실패
}
