package io.github.uou_capstone.aiplatform.domain.course.lecture.dto;


import io.github.uou_capstone.aiplatform.domain.course.lecture.AiGeneratedStatus;
import io.github.uou_capstone.aiplatform.domain.course.lecture.Lecture;
import lombok.Getter;

@Getter
public class LectureResponseDto { // 강의 목록에 간단한 정보만 담음
    private final Long lectureId;
    private final String title;
    private final int weekNumber;
    private final AiGeneratedStatus aiGeneratedStatus;

    public LectureResponseDto(Lecture lecture) {
        this.lectureId = lecture.getId();
        this.title = lecture.getTitle();
        this.weekNumber = lecture.getWeekNumber();
        this.aiGeneratedStatus = lecture.getAiGeneratedStatus();
    }
}