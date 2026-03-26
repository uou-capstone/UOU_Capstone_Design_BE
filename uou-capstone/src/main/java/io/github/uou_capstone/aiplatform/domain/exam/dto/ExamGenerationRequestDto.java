package io.github.uou_capstone.aiplatform.domain.exam.dto;

import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 시험 생성 요청 DTO
 */
@Getter
@Setter
public class ExamGenerationRequestDto {
    @NotNull(message = "강의 ID는 필수입니다.")
    private Long lectureId;

    /**
     * 시험 목록 표시명(사용자 입력).
     * 프론트가 displayName(camel) / display_name(snake) 중 하나로 보낼 수 있다.
     */
    @JsonAlias({"display_name"})
    private String displayName;

    /**
     * 시험 기준이 되는 PDF/자료 ID.
     * 없으면 강의의 최신 PDF를 사용한다.
     */
    private Long materialId;
    
    @NotNull(message = "시험 유형은 필수입니다.")
    private ExamType examType; // FLASH_CARD, OX_PROBLEM, FIVE_CHOICE, SHORT_ANSWER, DEBATE
    
    @Min(value = 1, message = "생성할 문제/카드 수는 1개 이상이어야 합니다.")
    private Integer targetCount; // 생성할 문제/카드 수 (기본값: 10)

    /**
     * 프론트에서 입력한 시험 주제(예: "프로세스와 스레드의 차이").
     * ProfileAgent 호출 시 context.topic 및 problem_count 로 전달됩니다.
     */
    private String topic;
    
    private String lectureContent; // 강의 자료 텍스트 (Markdown 등) - 선택적
    private TestProfileDto userProfile; // 사용자 프로필 (선택적, 없으면 자동 생성)
}
