package io.github.uou_capstone.aiplatform.agent.material;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.agent.AgentRequest;
import io.github.uou_capstone.aiplatform.agent.base.AbstractAgent;
import io.github.uou_capstone.aiplatform.domain.material.generation.dto.DraftPlanDto;
import io.github.uou_capstone.aiplatform.service.AgentPerformanceLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Planning Agent
 * Phase 1: 키워드 분석 및 DraftPlan 생성
 */
@Slf4j
public class PlanningAgent extends AbstractAgent {

    public PlanningAgent(WebClient aiServiceWebClient, ObjectMapper objectMapper, AgentPerformanceLogger performanceLogger) {
        super(aiServiceWebClient, objectMapper, performanceLogger, "PlanningAgent");
    }

    @Override
    protected String getEndpoint() {
        return "/api/lecture-gen/phase1/planning";
    }

    @Override
    protected Object buildRequestBody(AgentRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("prompt", request.getPrompt());
        body.put("context", request.getContext());
        return body;
    }

    /**
     * DraftPlan 생성
     */
    public DraftPlanDto generateDraftPlan(String keyword, String pdfPath) {
        Map<String, Object> context = new HashMap<>();
        context.put("keyword", keyword);
        context.put("pdf_path", pdfPath);

        AgentRequest request = new SimpleAgentRequest(keyword, context);
        return execute(request, DraftPlanDto.class);
    }

    /**
     * 간단한 AgentRequest 구현
     */
    private static class SimpleAgentRequest implements AgentRequest {
        private final String prompt;
        private final Map<String, Object> context;

        public SimpleAgentRequest(String prompt, Map<String, Object> context) {
            this.prompt = prompt;
            this.context = context;
        }

        @Override
        public String getPrompt() {
            return prompt;
        }

        @Override
        public Map<String, Object> getContext() {
            return context;
        }
    }
}
