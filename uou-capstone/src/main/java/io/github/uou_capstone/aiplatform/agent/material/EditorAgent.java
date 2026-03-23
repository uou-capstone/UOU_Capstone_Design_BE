package io.github.uou_capstone.aiplatform.agent.material;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.agent.AgentRequest;
import io.github.uou_capstone.aiplatform.agent.base.AbstractAgent;
import io.github.uou_capstone.aiplatform.domain.material.generation.dto.VerifiedContentDto;
import io.github.uou_capstone.aiplatform.service.AgentPerformanceLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Editor Agent
 * Phase 5: 최종 조립 및 수정 요청 반영
 */
@Slf4j
public class EditorAgent extends AbstractAgent {

    public EditorAgent(WebClient aiServiceWebClient, ObjectMapper objectMapper, AgentPerformanceLogger performanceLogger) {
        super(aiServiceWebClient, objectMapper, performanceLogger, "EditorAgent");
    }

    @Override
    protected String getEndpoint() {
        return "/api/v2/lecture-gen/phase5/editor";
    }

    @Override
    protected Object buildRequestBody(AgentRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("prompt", request.getPrompt());
        body.put("context", request.getContext());
        return body;
    }

    /**
     * 최종 문서 조립
     * @param verifiedContent 검증된 콘텐츠
     * @return 최종 Markdown 문서
     */
    public String assembleFinalDocument(VerifiedContentDto verifiedContent) {
        Map<String, Object> context = new HashMap<>();
        context.put("verified_content", verifiedContent);

        AgentRequest request = new SimpleAgentRequest("Assemble final Markdown document", context);
        Map<String, Object> result = execute(request, Map.class);
        return (String) result.get("finalDocument");
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
