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
 * Debate Phase 1 Agent
 * 토론형 시험 Phase 1에서 모드 설정 및 주제 선정
 * LLM_Generate_Question, LLM_Fill_Settings, LLM_Analyze_Intent 역할 통합
 */
@Slf4j
public class DebatePhase1Agent extends AbstractAgent {

    public DebatePhase1Agent(WebClient aiServiceWebClient, ObjectMapper objectMapper, AgentPerformanceLogger performanceLogger) {
        super(aiServiceWebClient, objectMapper, performanceLogger, "DebatePhase1Agent");
    }

    @Override
    protected String getEndpoint() {
        return "/api/test-gen/debate/phase1";
    }

    @Override
    protected Object buildRequestBody(AgentRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("prompt", request.getPrompt());
        body.put("context", request.getContext());
        return body;
    }
}
