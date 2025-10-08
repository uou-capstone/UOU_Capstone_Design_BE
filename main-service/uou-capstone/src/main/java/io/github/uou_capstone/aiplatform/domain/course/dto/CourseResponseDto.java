package io.github.uou_capstone.aiplatform.domain.course.dto;

import io.github.uou_capstone.aiplatform.domain.course.Course;
import lombok.Getter;

@Getter
public class CourseResponseDto { //과목생성

    private final Long courseId;
    private final String title;
    private final String description;
    private final String teacherName;

    public CourseResponseDto(Course course) {
        this.courseId = course.getId();
        this.title = course.getTitle();
        this.description = course.getDescription();
        this.teacherName = course.getTeacher().getUser().getFullName();
    }
}