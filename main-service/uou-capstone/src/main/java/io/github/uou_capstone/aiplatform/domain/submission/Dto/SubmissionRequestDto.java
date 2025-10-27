package io.github.uou_capstone.aiplatform.domain.submission.Dto;

import lombok.Getter;

import java.util.List;

@Getter
public class SubmissionRequestDto { //전체 제출물
    private List<StudentAnswerRequestDto> answers;
}