package io.github.uou_capstone.aiplatform.domain.course.report.dto.ai;

/**
 * FastAPI 가 받는 역량 수준 분류. 기존 {@code CompetencyStatus} 와 평면 1:1 이 아니라
 * 평균 점수까지 반영해 EXCELLENT 를 분리한다.
 */
public enum AiCompetencyLevel {
    EXCELLENT,
    GOOD,
    WATCH,
    NEEDS_IMPROVEMENT,
    INSUFFICIENT_DATA
}
