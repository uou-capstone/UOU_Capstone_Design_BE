package io.github.uou_capstone.aiplatform.domain.exam.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 강의별 시험 세션 목록 항목 (GET /api/exams/generation/lectures/{lectureId} 응답용)
 */
@Getter
@Builder
public class ExamSessionListItemDto {
    private Long examSessionId;
    private String examType;
    private String status;
    private Integer targetCount;
    private LocalDateTime createdAt;
}
