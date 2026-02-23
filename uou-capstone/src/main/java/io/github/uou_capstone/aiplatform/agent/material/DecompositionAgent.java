package io.github.uou_capstone.aiplatform.agent.material;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.agent.AgentRequest;
import io.github.uou_capstone.aiplatform.agent.base.AbstractAgent;
import io.github.uou_capstone.aiplatform.domain.material.generation.dto.ChapterContentListDto;
import io.github.uou_capstone.aiplatform.domain.material.generation.dto.FinalizedBriefDto;
import io.github.uou_capstone.aiplatform.service.AgentPerformanceLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Decomposition Agent
 * Phase 3: 챕터를 하위 주제로 분해
 */
@Slf4j
public class DecompositionAgent extends AbstractAgent {

    public DecompositionAgent(WebClient aiServiceWebClient, ObjectMapper objectMapper, AgentPerformanceLogger performanceLogger) {
        super(aiServiceWebClient, objectMapper, performanceLogger, "DecompositionAgent");
    }

    @Override
    protected String getEndpoint() {
        return "/api/lecture-gen/phase3/decomposition";
    }

    @Override
    protected Object buildRequestBody(AgentRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("prompt", request.getPrompt());
        body.put("context", request.getContext());
        return body;
    }

    /**
     * 챕터를 하위 주제로 분해
     * @param finalizedBrief 확정된 기획안
     * @param pdfPath PDF 파일 경로
     * @return 분해된 챕터 리스트
     */
    public ChapterContentListDto decomposeChapters(FinalizedBriefDto finalizedBrief, String pdfPath) {
        Map<String, Object> context = new HashMap<>();
        context.put("finalized_brief", finalizedBrief);
        context.put("pdf_path", pdfPath);

        AgentRequest request = new SimpleAgentRequest("Decompose chapters into subtopics", context);
        return execute(request, ChapterContentListDto.class);
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
