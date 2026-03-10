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
 * Write Agent
 * Phase 3: Markdown 본문 작성
 */
@Slf4j
public class WriteAgent extends AbstractAgent {

    public WriteAgent(WebClient aiServiceWebClient, ObjectMapper objectMapper, AgentPerformanceLogger performanceLogger) {
        super(aiServiceWebClient, objectMapper, performanceLogger, "WriteAgent");
    }

    @Override
    protected String getEndpoint() {
        return "/api/lecture-gen/phase3/write";
    }

    @Override
    protected Object buildRequestBody(AgentRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("prompt", request.getPrompt());
        body.put("context", request.getContext());
        return body;
    }

    /**
     * Markdown 본문 작성 (키워드/기획안 기반, PDF 미사용)
     * @param chapterContentList 분해된 챕터 리스트
     * @param pdfPath PDF 파일 경로 (미사용, null 전달)
     * @return 작성된 챕터 콘텐츠 리스트
     */
    public ChapterContentListDto writeContent(ChapterContentListDto chapterContentList, String pdfPath) {
        Map<String, Object> context = new HashMap<>();
        context.put("chapter_content_list", chapterContentList);
        if (pdfPath != null) {
            context.put("pdf_path", pdfPath);
        }

        AgentRequest request = new SimpleAgentRequest("Write Markdown content for chapters", context);
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
