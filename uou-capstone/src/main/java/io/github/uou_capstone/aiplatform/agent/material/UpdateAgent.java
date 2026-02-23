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
 * Update Agent
 * Phase 2: 사용자 피드백 기반 DraftPlan 수정
 * 
 * 역할:
 * - 사용자 피드백을 분석하여 DraftPlan 수정
 * - 수정된 DraftPlan 반환
 */
@Slf4j
public class UpdateAgent extends AbstractAgent {

    public UpdateAgent(WebClient aiServiceWebClient, ObjectMapper objectMapper, AgentPerformanceLogger performanceLogger) {
        super(aiServiceWebClient, objectMapper, performanceLogger, "UpdateAgent");
    }

    @Override
    protected String getEndpoint() {
        return "/api/lecture-gen/phase2/update";
    }

    @Override
    protected Object buildRequestBody(AgentRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("prompt", request.getPrompt());
        body.put("context", request.getContext());
        return body;
    }

    /**
     * DraftPlan 수정
     * 
     * @param draftPlan 현재 DraftPlan
     * @param userFeedback 사용자 피드백
     * @return 수정된 DraftPlan
     */
    public DraftPlanDto updateDraftPlan(DraftPlanDto draftPlan, String userFeedback) {
        Map<String, Object> context = new HashMap<>();
        context.put("draft_plan", draftPlan);
        context.put("user_feedback", userFeedback);

        AgentRequest request = new SimpleAgentRequest("Update draft plan based on user feedback", context);
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
