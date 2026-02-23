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
 * Evaluation Log Generator Agent
 * 토론형 시험 Phase 3에서 토론 로그 데이터를 생성
 */
@Slf4j
public class EvaluationLogGeneratorAgent extends AbstractAgent {

    public EvaluationLogGeneratorAgent(WebClient aiServiceWebClient, ObjectMapper objectMapper, AgentPerformanceLogger performanceLogger) {
        super(aiServiceWebClient, objectMapper, performanceLogger, "EvaluationLogGeneratorAgent");
    }

    @Override
    protected String getEndpoint() {
        return "/api/test-gen/debate/evaluation-log";
    }

    @Override
    protected Object buildRequestBody(AgentRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("prompt", request.getPrompt());
        body.put("context", request.getContext());
        return body;
    }
}
