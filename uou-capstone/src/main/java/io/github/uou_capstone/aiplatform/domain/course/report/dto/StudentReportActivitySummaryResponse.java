package io.github.uou_capstone.aiplatform.domain.course.report.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StudentReportActivitySummaryResponse {

    private final long questionCount;
    private final int examAttemptCount;
    private final int submissionCount;
    private final int missingSubmissionCount;
    private final Double lectureProgressPercent;
    private final List<PageCoverageItem> pageCoverage;
    private final List<CategoryCoverageItem> categoryCoverage;

    @Getter
    @Builder
    public static class PageCoverageItem {
        private final Integer pageNumber;
        private final long messageCount;
    }

    @Getter
    @Builder
    public static class CategoryCoverageItem {
        private final String category;
        private final long count;
    }
}
