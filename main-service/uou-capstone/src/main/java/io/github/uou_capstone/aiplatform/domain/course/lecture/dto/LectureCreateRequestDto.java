package io.github.uou_capstone.aiplatform.domain.course.lecture.dto;

import lombok.Getter;

@Getter
public class LectureCreateRequestDto {
    private String title;
    private int weekNumber;
    private String description;
}