package io.github.uou_capstone.aiplatform.agent.exam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.agent.AgentRequest;
import io.github.uou_capstone.aiplatform.agent.base.AbstractAgent;
import io.github.uou_capstone.aiplatform.domain.exam.dto.GradingResponseDto;
import io.github.uou_capstone.aiplatform.domain.exam.dto.TestProfileDto;
import io.github.uou_capstone.aiplatform.domain.exam.dto.UserFeedbackProfileDto;
import io.github.uou_capstone.aiplatform.service.AgentPerformanceLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Feedback Generator Agent (Generator_feedBack)
 * 시험 응시 후 사용자 피드백 프로필 생성 Agent
 * 
 * 역할:
 * - 시험 응시 결과를 분석하여 사용자 피드백 프로필 생성
 * - 개별 문제 평가 결과를 종합하여 학습 패턴 분석
 * - 다음 시험 생성 시 활용할 수 있는 피드백 정보 제공
 */
@Slf4j
public class FeedbackGeneratorAgent extends AbstractAgent {

    public FeedbackGeneratorAgent(WebClient aiServiceWebClient, ObjectMapper objectMapper, AgentPerformanceLogger performanceLogger) {
        super(aiServiceWebClient, objectMapper, performanceLogger, "FeedbackGeneratorAgent");
    }

    @Override
    protected String getEndpoint() {
        return "/api/test-gen/feedback";
    }

    @Override
    protected Object buildRequestBody(AgentRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("prompt", request.getPrompt());
        body.put("context", request.getContext());
        return body;
    }

    /**
     * 사용자 피드백 프로필 생성
     * 
     * 시험 응시 결과를 분석하여 사용자의 학습 패턴과 약점을 파악하고,
     * 다음 시험 생성 시 활용할 수 있는 피드백 프로필을 생성합니다.
     * 
     * @param priorProfile 사전 Profile (시험 생성 시 사용된 Profile)
     * @param gradingResult 채점 결과 (GradingResponseDto)
     * @param examContent 시험 내용 (문제 리스트)
     * @return 생성된 UserFeedbackProfileDto
     */
    public UserFeedbackProfileDto generateUserFeedback(
            TestProfileDto priorProfile,
            GradingResponseDto gradingResult,
            Map<String, Object> examContent) {
        
        Map<String, Object> context = new HashMap<>();
        context.put("prior_profile", priorProfile);
        context.put("grading_result", gradingResult);
        context.put("exam_content", examContent);

        AgentRequest request = new SimpleAgentRequest(
                "Generate user feedback profile based on exam results", 
                context
        );
        
        return execute(request, UserFeedbackProfileDto.class);
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
