package io.github.uou_capstone.aiplatform.domain.assessment.dto;

import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamQuestion;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import lombok.Getter;

import java.util.List;
import java.util.stream.Collectors;

@Getter
public class QuestionResponseDto { /// 문제 정보 + 선택지 목록 (v2: ExamQuestion 사용)
    private final Long questionId;
    private final String text;
    private final ExamType type;  // v2: ExamType 사용
    private final List<OptionResponseDto> options;

    public QuestionResponseDto(ExamQuestion question) {
        this.questionId = question.getId();
        this.text = question.getQuestionContent();  // v2: getText() → getQuestionContent()
        this.type = question.getExamType();  // v2: getType() → getExamType()

        // ExamQuestion 엔티티에서 바로 choiceOptions 리스트를 가져와 변환
        this.options = question.getChoiceOptions().stream()
                .map(OptionResponseDto::new)
                .collect(Collectors.toList());
    }
}