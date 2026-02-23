package io.github.uou_capstone.aiplatform.agent;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * 스트리밍 이벤트 DTO
 * Agent 추론 과정을 실시간으로 전달하는 이벤트
 */
@Getter
@Builder
public class StreamingEvent {
    private String type; // "thought", "answer", "error"
    private String delta; // 증분 텍스트 (스트리밍용)
    private ThoughtNode content; // 완전한 Thought Node
    private Map<String, Object> metadata; // 추가 메타데이터
}
