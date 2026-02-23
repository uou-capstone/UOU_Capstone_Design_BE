package io.github.uou_capstone.aiplatform.domain.exam.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 세션 메타 정보
 */
@Getter
@Setter
public class SessionMetaDto {
    private Long examSessionId;
    private Long userId;
    private String examType;
    private Long completedAt; // 타임스탬프
    private Integer totalQuestions;
    private Integer answeredQuestions;
}
