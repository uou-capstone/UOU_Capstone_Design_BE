package io.github.uou_capstone.aiplatform.domain.course.report.dto.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * AI 가 참조할 근거 단위. type 은 "exam" 또는 "submission".
 *
 * - sourceId : ExamResult.id 또는 Submission.id
 * - summary  : 한 줄 요약 (FE/AI 가 바로 읽을 수 있는 형태)
 * - rawText  : AI 분석에 사용할 원문 피드백/총평. 없으면 fallback 문자열.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiEvidenceItemDto {
    private final String type;
    private final Long sourceId;
    private final String summary;
    private final String rawText;
    private final LocalDateTime occurredAt;
}
