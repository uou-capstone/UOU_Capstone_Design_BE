package io.github.uou_capstone.aiplatform.domain.course.report.dto.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 기존 {@code NarrativeReportDto} 의 일부를 AI 입력용으로 재배열한 형태.
 * - improvements → weaknesses 로 명칭 변경
 * - nextSteps 는 노출하지 않음 (AI 가 자체 생성)
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiNarrativeDto {
    private final String summary;
    private final List<String> strengths;
    private final List<String> weaknesses;
}
