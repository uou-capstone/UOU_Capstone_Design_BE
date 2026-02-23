package io.github.uou_capstone.aiplatform.agent;

import lombok.Builder;
import lombok.Getter;

/**
 * ThoughtNode DTO
 * Agent의 추론 과정 단위
 */
@Getter
@Builder
public class ThoughtNode {
    private String stepId; // 단계별 고유 식별자
    private String content; // 실시간 스트리밍되는 텍스트 내용
    private String status; // "processing", "completed", "failed"
    private String visualType; // "flowchart", "math_block", "graph" (선택적)
    private String vizType; // "flowchart", "graph", "tree", "sequence" (선택적)
    private String vizData; // 시각화 데이터 (Mermaid.js, KaTeX 등)
    private Double timestamp; // 타임스탬프
}
