package io.github.uou_capstone.aiplatform.agent.material;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.agent.AgentRequest;
import io.github.uou_capstone.aiplatform.agent.base.AbstractAgent;
import io.github.uou_capstone.aiplatform.domain.material.generation.dto.DraftPlanDto;
import io.github.uou_capstone.aiplatform.domain.material.generation.dto.Phase1ResponseWrapper;
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
        // ko 브랜치 엔드포인트 형식에 맞게 변환
        // 요청 형식: { "topic": "...", "audience_level": "..." }
        Map<String, Object> body = new HashMap<>();
        Map<String, Object> context = request.getContext();
        
        // topic 변환: context의 keyword 또는 prompt 사용
        String topic = context != null && context.containsKey("keyword") 
            ? (String) context.get("keyword") 
            : request.getPrompt();
        body.put("topic", topic);
        
        // audience_level 기본값 설정
        body.put("audience_level", "University Students");
        
        return body;
    }

    /**
     * DraftPlan 생성
     * 
     * @param keyword 사용자가 입력한 키워드
     * @return 생성된 DraftPlan
     */
    public DraftPlanDto generateDraftPlan(String keyword) {
        Map<String, Object> context = new HashMap<>();
        context.put("keyword", keyword);

        AgentRequest request = new SimpleAgentRequest(keyword, context);
        // ko 브랜치 응답 형식: { "draft_plan": {...} }
        Phase1ResponseWrapper wrapper = execute(request, Phase1ResponseWrapper.class);
        return wrapper != null ? wrapper.getDraftPlan() : null;
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
