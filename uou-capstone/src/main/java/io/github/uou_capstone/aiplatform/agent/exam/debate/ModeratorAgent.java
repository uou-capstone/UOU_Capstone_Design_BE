package io.github.uou_capstone.aiplatform.agent.exam.debate;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.agent.AgentRequest;
import io.github.uou_capstone.aiplatform.agent.base.AbstractAgent;
import io.github.uou_capstone.aiplatform.service.AgentPerformanceLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Moderator Agent
 * 토론형 시험 Phase 2에서 사회자 역할을 수행
 * 사용자 입력을 검증하고 토론 진행을 관리
 */
@Slf4j
public class ModeratorAgent extends AbstractAgent {

    public ModeratorAgent(WebClient aiServiceWebClient, ObjectMapper objectMapper, AgentPerformanceLogger performanceLogger) {
        super(aiServiceWebClient, objectMapper, performanceLogger, "ModeratorAgent");
    }

    @Override
    protected String getEndpoint() {
        return "/api/test-gen/debate/moderator";
    }

    @Override
    protected Object buildRequestBody(AgentRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("prompt", request.getPrompt());
        body.put("context", request.getContext());
        return body;
    }
}
