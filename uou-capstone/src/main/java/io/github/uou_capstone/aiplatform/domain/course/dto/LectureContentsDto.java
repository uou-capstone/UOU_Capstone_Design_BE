package io.github.uou_capstone.aiplatform.domain.course.dto;

import lombok.Getter;

import java.util.List;

/**
 * n주차(강의) 단위의 강의자료·시험 목록
 */
@Getter
public class LectureContentsDto {
    private final Long lectureId;
    private final String title;
    private final int weekNumber;
    private final List<MaterialSummaryDto> materials;
    private final List<ExamSessionSummaryDto> examSessions;

    public LectureContentsDto(Long lectureId, String title, int weekNumber,
                              List<MaterialSummaryDto> materials,
                              List<ExamSessionSummaryDto> examSessions) {
        this.lectureId = lectureId;
        this.title = title;
        this.weekNumber = weekNumber;
        this.materials = materials != null ? materials : List.of();
        this.examSessions = examSessions != null ? examSessions : List.of();
    }
}
