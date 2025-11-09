package io.github.uou_capstone.aiplatform.domain.course.lecture.dto;

import lombok.Getter;

@Getter
public class LectureUpdateRequestDto {
    private String title;
    private Integer weekNumber; // int 대신 Integer를 사용하여 null 체크
    private String description;
}