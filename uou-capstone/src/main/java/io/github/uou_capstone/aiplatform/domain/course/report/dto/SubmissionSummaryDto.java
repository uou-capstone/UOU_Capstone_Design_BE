package io.github.uou_capstone.aiplatform.domain.course.report.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SubmissionSummaryDto {
    private final int submittedCount;   // 제출 완료 (Submission row 존재)
    private final int gradedCount;      // 채점 완료 (examResult 연결됨)
    private final int pendingCount;     // 채점 대기 (Submission 존재, examResult 없음)
    private final int missingCount;     // 미제출 (강의실 Assessment 중 학생 Submission 없는 것)
}
