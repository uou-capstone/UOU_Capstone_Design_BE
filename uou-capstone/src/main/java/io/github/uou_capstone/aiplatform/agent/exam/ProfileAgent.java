package io.github.uou_capstone.aiplatform.agent.exam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.agent.AgentRequest;
import io.github.uou_capstone.aiplatform.agent.base.AbstractAgent;
import io.github.uou_capstone.aiplatform.domain.exam.dto.TestProfileDto;
import io.github.uou_capstone.aiplatform.service.AgentPerformanceLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Profile Agent
 * 시험 생성 전 Profile 완성 여부 판단 및 생성
 */
@Slf4j
public class ProfileAgent extends AbstractAgent {

    public ProfileAgent(WebClient aiServiceWebClient, ObjectMapper objectMapper, AgentPerformanceLogger performanceLogger) {
        super(aiServiceWebClient, objectMapper, performanceLogger, "ProfileAgent");
    }

    @Override
    protected String getEndpoint() {
        return "/api/test-gen/profile";
    }

    @Override
    protected Object buildRequestBody(AgentRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("prompt", request.getPrompt());
        body.put("context", request.getContext());
        return body;
    }

    /**
     * Profile 생성 또는 검증
     * @param lectureContent 강의 자료 내용
     * @param existingProfile 기존 Profile (선택적)
     * @return 생성/검증된 Profile
     */
    public TestProfileDto generateOrValidateProfile(String lectureContent, TestProfileDto existingProfile) {
        Map<String, Object> context = new HashMap<>();
        context.put("lecture_content", lectureContent);
        if (existingProfile != null) {
            context.put("existing_profile", existingProfile);
        }

        AgentRequest request = new SimpleAgentRequest("Generate or validate test profile", context);
        return execute(request, TestProfileDto.class);
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
