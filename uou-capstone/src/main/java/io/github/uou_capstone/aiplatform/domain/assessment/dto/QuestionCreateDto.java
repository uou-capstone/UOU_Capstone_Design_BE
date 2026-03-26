package io.github.uou_capstone.aiplatform.domain.assessment.dto;


import com.fasterxml.jackson.annotation.JsonAlias;
import io.github.uou_capstone.aiplatform.domain.assessment.entity.CreatedBy;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import lombok.Getter;

import java.util.List;

/** 평가 문제 생성/수신 공용 DTO. snake_case 별칭 허용. */
@Getter
public class QuestionCreateDto {
    @JsonAlias({"question_text", "question_content", "question"})
    private String text;

    /** Java enum 이름과 동일한 문자열 (예: FIVE_CHOICE). Bridge 스타일 Five_Choice는 별도 매핑 필요. */
    @JsonAlias({"exam_type"})
    private ExamType type;

    private CreatedBy createdBy;

    @JsonAlias({"choice_options"})
    private List<ChoiceOptionCreateDto> choiceOptions;
}
