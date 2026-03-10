package io.github.uou_capstone.aiplatform.domain.course.dto;

import lombok.Getter;

import java.util.List;

/**
 * 강의실 내 n주차별 강의자료·시험 목록 조회 응답
 */
@Getter
public class CourseContentsResponseDto {
    private final Long courseId;
    private final String courseTitle;
    private final List<LectureContentsDto> lectures;

    public CourseContentsResponseDto(Long courseId, String courseTitle, List<LectureContentsDto> lectures) {
        this.courseId = courseId;
        this.courseTitle = courseTitle;
        this.lectures = lectures != null ? lectures : List.of();
    }
}
