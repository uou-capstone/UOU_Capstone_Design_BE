package io.github.uou_capstone.aiplatform.agent.exam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.agent.AgentRequest;
import io.github.uou_capstone.aiplatform.agent.base.AbstractAgent;
import io.github.uou_capstone.aiplatform.domain.exam.dto.FiveChoiceProblemDto;
import io.github.uou_capstone.aiplatform.domain.exam.dto.TestProfileDto;
import io.github.uou_capstone.aiplatform.service.AgentPerformanceLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Five Choice Generator Agent
 * 5지선다 문제 생성 Agent
 */
@Slf4j
public class FiveChoiceGeneratorAgent extends AbstractAgent {

    public FiveChoiceGeneratorAgent(WebClient aiServiceWebClient, ObjectMapper objectMapper, AgentPerformanceLogger performanceLogger) {
        super(aiServiceWebClient, objectMapper, performanceLogger, "FiveChoiceGeneratorAgent");
    }

    @Override
    protected String getEndpoint() {
        return "/api/test-gen/five-choice";
    }

    @Override
    protected Object buildRequestBody(AgentRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("prompt", request.getPrompt());
        body.put("context", request.getContext());
        return body;
    }

    /**
     * 5지선다 문제 생성
     * @param lectureContent 강의 자료 내용
     * @param profile 사용자 Profile
     * @param targetCount 생성할 문제 수
     * @return 생성된 5지선다 문제 리스트
     */
    public List<FiveChoiceProblemDto> generateFiveChoiceProblems(
            String lectureContent, 
            TestProfileDto profile, 
            Integer targetCount) {
        Map<String, Object> context = new HashMap<>();
        context.put("lecture_content", lectureContent);
        context.put("profile", profile);
        context.put("target_count", targetCount);

        AgentRequest request = new SimpleAgentRequest("Generate five-choice problems", context);
        return execute(request, List.class);
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
