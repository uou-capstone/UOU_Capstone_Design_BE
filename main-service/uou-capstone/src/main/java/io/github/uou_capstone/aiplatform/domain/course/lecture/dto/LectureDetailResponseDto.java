package io.github.uou_capstone.aiplatform.domain.course.lecture.dto;

import io.github.uou_capstone.aiplatform.domain.course.lecture.AiGeneratedStatus;
import io.github.uou_capstone.aiplatform.domain.course.lecture.GeneratedContent;
import io.github.uou_capstone.aiplatform.domain.course.lecture.Lecture;
import lombok.Getter;

import java.util.List;
import java.util.stream.Collectors;

@Getter
public class LectureDetailResponseDto {
    private final Long lectureId;
    private final String title;
    private final int weekNumber;
    private final String description;
    private final AiGeneratedStatus aiGeneratedStatus;
    private final List<GeneratedContentResponseDto> contents; // AI 생성 콘텐츠 목록

    // 생성자: Lecture 엔티티와 GeneratedContent 엔티티 목록을 받아서 DTO 생성
    public LectureDetailResponseDto(Lecture lecture, List<GeneratedContent> generatedContents) {
        this.lectureId = lecture.getId();
        this.title = lecture.getTitle();
        this.weekNumber = lecture.getWeekNumber();
        this.description = lecture.getDescription();
        this.aiGeneratedStatus = lecture.getAiGeneratedStatus();
        this.contents = generatedContents.stream()
                .map(GeneratedContentResponseDto::new)
                .collect(Collectors.toList());
    }
}