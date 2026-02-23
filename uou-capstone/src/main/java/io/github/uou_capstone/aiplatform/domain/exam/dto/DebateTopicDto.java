package io.github.uou_capstone.aiplatform.domain.exam.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 토론형 문제 DTO
 * DEBATE 유형 문제
 */
@Getter
@Setter
public class DebateTopicDto {
    private String topic; // 토론 주제
    private String context; // 배경 설명
    private String proSideStand; // 찬성 측 입장
    private String conSideStand; // 반대 측 입장
    private List<String> evaluationCriteria; // 평가 기준 목록
}
