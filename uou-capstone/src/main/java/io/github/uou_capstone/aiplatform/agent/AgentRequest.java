package io.github.uou_capstone.aiplatform.agent;

import java.util.Map;

/**
 * Agent 요청 인터페이스
 * 모든 Agent가 공통으로 사용하는 요청 형식
 */
public interface AgentRequest {
    /**
     * Agent에게 전달할 프롬프트
     */
    String getPrompt();

    /**
     * 추가 컨텍스트 정보 (JSON 등)
     */
    Map<String, Object> getContext();
}
