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
 * Confirm Agent
 * Phase 2: 사용자 피드백 분석 (승인/수정 판단)
 */
@Slf4j
public class ConfirmAgent extends AbstractAgent {

    public ConfirmAgent(WebClient aiServiceWebClient, ObjectMapper objectMapper, AgentPerformanceLogger performanceLogger) {
        super(aiServiceWebClient, objectMapper, performanceLogger, "ConfirmAgent");
    }

    @Override
    protected String getEndpoint() {
        return "/api/v2/lecture-gen/phase2/confirm";
    }

    @Override
    protected Object buildRequestBody(AgentRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("prompt", request.getPrompt());
        body.put("context", request.getContext());
        return body;
    }

    /**
     * 사용자 피드백 분석
     * @param draftPlan 초기 기획안
     * @param userFeedback 사용자 피드백
     * @return 승인 여부 및 수정 사항
     */
    public Map<String, Object> analyzeFeedback(DraftPlanDto draftPlan, String userFeedback) {
        Map<String, Object> context = new HashMap<>();
        context.put("draft_plan", draftPlan);
        context.put("user_feedback", userFeedback);

        AgentRequest request = new SimpleAgentRequest("Analyze user feedback", context);
        return execute(request, Map.class);
    }

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
