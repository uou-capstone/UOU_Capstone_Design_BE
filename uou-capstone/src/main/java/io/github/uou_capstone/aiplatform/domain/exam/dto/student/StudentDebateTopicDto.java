package io.github.uou_capstone.aiplatform.domain.exam.dto.student;

import lombok.Builder;
import lombok.Getter;

/**
 * 학생용 토론 토픽 — proSideStand / conSideStand / evaluationCriteria 는 포함하지 않는다.
 *
 * <p>현재 {@code ExamGenerationService} 가 DEBATE 토픽을 채우지 않으므로 학생 조회 응답의
 * {@code debateTopics} 는 항상 빈 리스트다. 실제 토론은 {@code /api/exams/debate/start}
 * 에서 시작한다.
 */
@Getter
@Builder
public class StudentDebateTopicDto {
    private Long id;             // ExamQuestion.id
    private String topic;
    private String context;
}
