package io.github.uou_capstone.aiplatform.agent.exam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.uou_capstone.aiplatform.agent.AgentRequest;
import io.github.uou_capstone.aiplatform.agent.base.AbstractAgent;
import io.github.uou_capstone.aiplatform.domain.exam.dto.ProfileConversationResponseDto;
import io.github.uou_capstone.aiplatform.domain.exam.dto.TestProfileDto;
import io.github.uou_capstone.aiplatform.service.AgentPerformanceLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
     * FastAPI POST /api/test-gen/profile 응답에서 updated_profile만 추출해 반환합니다.
     *
     * @param lectureContent 강의 자료 내용
     * @param examType 시험 유형 (예: Flash_Card, Short_Answer 등) - 없으면 Flash_Card 로 기본 처리될 수 있음
     * @param topic 프론트에서 선택한 주제(선택)
     * @param problemCount 프론트에서 선택한 문제 수(선택)
     * @param existingProfile 기존 Profile (선택적)
     * @return 생성/검증된 Profile (updated_profile)
     */
    @SuppressWarnings("unchecked")
    public TestProfileDto generateOrValidateProfile(
            String lectureContent,
            String examType,
            String topic,
            Integer problemCount,
            TestProfileDto existingProfile
    ) {
        Map<String, Object> context = new HashMap<>();
        context.put("lecture_content", lectureContent);
        if (examType != null && !examType.isBlank()) {
            context.put("exam_type", examType);
        }
        if (topic != null && !topic.isBlank()) {
            context.put("topic", topic);
        }
        if (problemCount != null && problemCount > 0) {
            context.put("problem_count", problemCount);
        }
        if (existingProfile != null) {
            context.put("existing_profile", existingProfile);
        }

        String prompt = "Generate or validate test profile.";
        if (examType != null && !examType.isBlank()) {
            prompt += " Exam type for this request: " + examType + ". Do not assume Flash_Card.";
        }
        AgentRequest request = new SimpleAgentRequest(prompt, context);
        Map<String, Object> response = execute(request, (Class<Map<String, Object>>) (Class<?>) Map.class);

        Object updated = response != null ? response.get("updated_profile") : null;
        if (updated == null || !(updated instanceof Map)) {
            throw new IllegalStateException("FastAPI profile response missing updated_profile");
        }

        // FastAPI는 snake_case로 반환하므로 snake_case → TestProfileDto 변환
        ObjectMapper snakeMapper = objectMapper.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return snakeMapper.convertValue(updated, TestProfileDto.class);
    }

    /**
     * 프로필 대화 1턴 (문서: ai-service-endpoint-request.md §2)
     * context에 lecture_content, exam_type, existing_profile(snake_case), user_message 전달 후
     * 전체 응답(status, agent_message, missing_info, updated_profile) 반환.
     */
    @SuppressWarnings("unchecked")
    public ProfileConversationResponseDto chatTurn(String lectureContent, String examType,
                                                   TestProfileDto existingProfile, String userMessage) {
        Map<String, Object> context = new HashMap<>();
        context.put("lecture_content", lectureContent);
        if (examType != null && !examType.isBlank()) {
            context.put("exam_type", examType);
        }
        if (existingProfile != null) {
            // FastAPI는 snake_case 기대
            ObjectMapper snakeMapper = objectMapper.copy()
                    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
            Map<String, Object> profileMap = snakeMapper.convertValue(existingProfile, new TypeReference<Map<String, Object>>() {});
            context.put("existing_profile", profileMap);
        }
        if (userMessage != null && !userMessage.isBlank()) {
            context.put("user_message", userMessage);
        }

        AgentRequest request = new SimpleAgentRequest("Generate or validate test profile", context);
        Map<String, Object> response = execute(request, (Class<Map<String, Object>>) (Class<?>) Map.class);
        if (response == null) {
            throw new IllegalStateException("FastAPI profile response is null");
        }

        String status = (String) response.get("status");
        String agentMessage = (String) response.get("agent_message");
        List<String> missingInfo = response.get("missing_info") instanceof List
                ? (List<String>) response.get("missing_info")
                : new ArrayList<>();
        Object updated = response.get("updated_profile");

        TestProfileDto updatedProfileDto = null;
        if (updated instanceof Map) {
            ObjectMapper snakeMapper = objectMapper.copy()
                    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
            updatedProfileDto = snakeMapper.convertValue(updated, TestProfileDto.class);
        }

        return ProfileConversationResponseDto.builder()
                .status(status != null ? status : "INCOMPLETE")
                .agentMessage(agentMessage != null ? agentMessage : "")
                .missingInfo(missingInfo != null ? missingInfo : new ArrayList<>())
                .updatedProfile(updatedProfileDto)
                .build();
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
