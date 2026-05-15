package io.github.uou_capstone.aiplatform.domain.exam.dto.student;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 학생용 5지선다 — correctAnswer / intentDiagnosis / option.intent / option.isCorrect 는 포함하지 않는다.
 *
 * <p>options[].id 는 원본 옵션 번호 문자열 ("1"~"5") 이다. (DB ChoiceOption.id 가 아님)
 * 학생 응시 요청의 selectedOptionId 도 같은 도메인("1"~"5") 을 보낸다.
 */
@Getter
@Builder
public class StudentFiveChoiceProblemDto {
    private Long id;             // ExamQuestion.id
    private String questionContent;
    private List<Option> options;

    @Getter
    @Builder
    public static class Option {
        private String id;       // "1" ~ "5"
        private String content;
    }
}
