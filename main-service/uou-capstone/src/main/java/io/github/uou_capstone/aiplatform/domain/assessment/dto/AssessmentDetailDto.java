package io.github.uou_capstone.aiplatform.domain.assessment.dto;

import io.github.uou_capstone.aiplatform.domain.assessment.Assessment;
import io.github.uou_capstone.aiplatform.domain.assessment.AssessmentType;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Getter
public class AssessmentDetailDto { /// (평가 상세 정보 + 문제 목록)
    private final Long assessmentId;
    private final String title;
    private final AssessmentType type;
    private final LocalDateTime dueDate;
    private final List<QuestionResponseDto> questions;

    public AssessmentDetailDto(Assessment assessment) {
        this.assessmentId = assessment.getId();
        this.title = assessment.getTitle();
        this.type = assessment.getType();
        this.dueDate = assessment.getDueDate();
        // Assessment 엔티티에서 바로 questions 리스트를 가져와 변환
        this.questions = assessment.getQuestions().stream()
                .map(QuestionResponseDto::new)
                .collect(Collectors.toList());
    }
}