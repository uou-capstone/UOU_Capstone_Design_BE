package io.github.uou_capstone.aiplatform.domain.course.report.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 강의실 학생 리포트 리스트 — 학생 1명 항목 (요약 지표).
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StudentReportListItem {
    private final Long studentId;
    private final Long userId;
    private final String studentName;
    private final Double averageScorePercent;
    private final int examAttemptCount;
    private final int submissionCount;
    private final LocalDateTime latestActivityAt;
    private final String reportStatus;
    private final String topStrengthLabel;
    private final String topImprovementLabel;
}
