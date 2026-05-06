package io.github.uou_capstone.aiplatform.domain.course.report.dto.ai;

/**
 * 학생의 시험 점수 변화 추세. 시간 오름차순 정렬 후 첫 점수와 마지막 점수의 차이로 분류.
 */
public enum AiScoreTrend {
    IMPROVING,
    STABLE,
    DECLINING,
    INSUFFICIENT_DATA
}
