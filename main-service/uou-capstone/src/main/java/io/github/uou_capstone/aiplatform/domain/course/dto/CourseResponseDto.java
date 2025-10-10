package io.github.uou_capstone.aiplatform.domain.course.dto;

import io.github.uou_capstone.aiplatform.domain.course.Course;
import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.LectureResponseDto;
import lombok.Getter;

import java.util.List;
import java.util.stream.Collectors;

@Getter
public class CourseResponseDto { //과목생성

    private final Long courseId;
    private final String title;
    private final String description;
    private final String teacherName;
    private final List<LectureResponseDto> lectures;

    public CourseResponseDto(Course course) {
        this.courseId = course.getId();
        this.title = course.getTitle();
        this.description = course.getDescription();
        this.teacherName = course.getTeacher().getUser().getFullName();
        this.lectures = course.getLectures().stream()
                .map(LectureResponseDto::new)
                .collect(Collectors.toList());
    }
}