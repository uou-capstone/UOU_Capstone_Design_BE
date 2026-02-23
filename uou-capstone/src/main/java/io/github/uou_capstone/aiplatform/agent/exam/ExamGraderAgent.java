package io.github.uou_capstone.aiplatform.agent.exam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.agent.AgentRequest;
import io.github.uou_capstone.aiplatform.agent.base.AbstractAgent;
import io.github.uou_capstone.aiplatform.domain.exam.dto.GradingResponseDto;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import io.github.uou_capstone.aiplatform.service.AgentPerformanceLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Exam Grader Agent
 * 시험 채점 Agent (시험 유형별로 분기)
 */
@Slf4j
public class ExamGraderAgent extends AbstractAgent {

    public ExamGraderAgent(WebClient aiServiceWebClient, ObjectMapper objectMapper, AgentPerformanceLogger performanceLogger) {
        super(aiServiceWebClient, objectMapper, performanceLogger, "ExamGraderAgent");
    }

    @Override
    protected String getEndpoint() {
        return "/api/test-gen/grade";
    }

    @Override
    protected Object buildRequestBody(AgentRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("prompt", request.getPrompt());
        body.put("context", request.getContext());
        return body;
    }

    /**
     * 시험 채점
     * @param examType 시험 유형
     * @param examContent 시험 내용 (문제 리스트)
     * @param userAnswers 사용자 답변
     * @return 채점 결과
     */
    public GradingResponseDto gradeExam(ExamType examType, Map<String, Object> examContent, Map<String, Object> userAnswers) {
        Map<String, Object> context = new HashMap<>();
        context.put("exam_type", examType.name());
        context.put("exam_content", examContent);
        context.put("user_answers", userAnswers);

        AgentRequest request = new SimpleAgentRequest("Grade exam", context);
        return execute(request, GradingResponseDto.class);
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
