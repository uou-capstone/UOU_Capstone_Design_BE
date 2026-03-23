package io.github.uou_capstone.aiplatform.agent.material;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.agent.AgentRequest;
import io.github.uou_capstone.aiplatform.agent.base.AbstractAgent;
import io.github.uou_capstone.aiplatform.domain.material.generation.dto.VerifiedContentDto;
import io.github.uou_capstone.aiplatform.domain.material.generation.dto.ChapterContentListDto;
import io.github.uou_capstone.aiplatform.service.AgentPerformanceLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Review Agent
 * Phase 4: 품질 검증
 */
@Slf4j
public class ReviewAgent extends AbstractAgent {

    public ReviewAgent(WebClient aiServiceWebClient, ObjectMapper objectMapper, AgentPerformanceLogger performanceLogger) {
        super(aiServiceWebClient, objectMapper, performanceLogger, "ReviewAgent");
    }

    @Override
    protected String getEndpoint() {
        return "/api/v2/lecture-gen/phase4/review";
    }

    @Override
    protected Object buildRequestBody(AgentRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("prompt", request.getPrompt());
        body.put("context", request.getContext());
        return body;
    }

    /**
     * 품질 검증
     * @param chapterContentList 작성된 챕터 콘텐츠 리스트
     * @return 검증된 콘텐츠
     */
    public VerifiedContentDto reviewContent(ChapterContentListDto chapterContentList) {
        Map<String, Object> context = new HashMap<>();
        context.put("chapter_content_list", chapterContentList);

        AgentRequest request = new SimpleAgentRequest("Review and verify content quality", context);
        return execute(request, VerifiedContentDto.class);
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
