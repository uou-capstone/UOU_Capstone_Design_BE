package io.github.uou_capstone.aiplatform.domain.course.dto;

import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.LectureResponseDto;
import lombok.Getter;

import java.util.List;
import java.util.stream.Collectors;

@Getter
public class CourseResponseDto { // 강의실 조회 응답 DTO

    private final Long courseId;
    private final String title;
    private final String description;
    private final String teacherName;
    private final String invitationCode; // 인증코드 추가
    private final List<LectureResponseDto> lectures;

    public CourseResponseDto(Course course) {
        this.courseId = course.getId();
        this.title = course.getTitle();
        this.description = course.getDescription();
        this.teacherName = course.getTeacher().getUser().getFullName();
        this.invitationCode = course.getInvitationCode(); // 엔티티에서 가져옴
        this.lectures = course.getLectures().stream()
                .map(LectureResponseDto::new)
                .collect(Collectors.toList());
    }
}
