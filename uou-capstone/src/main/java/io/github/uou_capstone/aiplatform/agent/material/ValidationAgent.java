package io.github.uou_capstone.aiplatform.agent.material;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.agent.AgentRequest;
import io.github.uou_capstone.aiplatform.agent.base.AbstractAgent;
import io.github.uou_capstone.aiplatform.domain.material.generation.dto.ChapterContentListDto;
import io.github.uou_capstone.aiplatform.service.AgentPerformanceLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Validation Agent
 * Phase 4: 검색 결과 충분성 검증
 */
@Slf4j
public class ValidationAgent extends AbstractAgent {

    public ValidationAgent(WebClient aiServiceWebClient, ObjectMapper objectMapper, AgentPerformanceLogger performanceLogger) {
        super(aiServiceWebClient, objectMapper, performanceLogger, "ValidationAgent");
    }

    @Override
    protected String getEndpoint() {
        return "/api/v2/lecture-gen/phase4/validation";
    }

    @Override
    protected Object buildRequestBody(AgentRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("prompt", request.getPrompt());
        body.put("context", request.getContext());
        return body;
    }

    /**
     * 검색 결과 충분성 검증
     * @param chapterContentList 작성된 챕터 콘텐츠 리스트
     * @return 검증 결과
     */
    public Map<String, Object> validateContent(ChapterContentListDto chapterContentList) {
        Map<String, Object> context = new HashMap<>();
        context.put("chapter_content_list", chapterContentList);

        AgentRequest request = new SimpleAgentRequest("Validate content sufficiency", context);
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
