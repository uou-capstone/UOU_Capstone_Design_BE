package io.github.uou_capstone.aiplatform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.agent.material.ConfirmAgent;
import io.github.uou_capstone.aiplatform.agent.material.DecompositionAgent;
import io.github.uou_capstone.aiplatform.agent.material.EditorAgent;
import io.github.uou_capstone.aiplatform.agent.material.PlanningAgent;
import io.github.uou_capstone.aiplatform.agent.material.ReviewAgent;
import io.github.uou_capstone.aiplatform.agent.material.UpdateAgent;
import io.github.uou_capstone.aiplatform.agent.material.ValidationAgent;
import io.github.uou_capstone.aiplatform.agent.material.WriteAgent;
import io.github.uou_capstone.aiplatform.service.AgentPerformanceLogger;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Agent Bean 등록 Configuration (v3)
 *
 * v3 전환 후 exam Agent(13개)는 FastAPI /bridge/quiz, /bridge/grade, /api/session/event 위임으로 대체되어 삭제됨.
 * material Agent(8개)는 강의 자료 생성 파이프라인에서 그대로 유지됨.
 */
@Configuration
@RequiredArgsConstructor
public class AgentConfig {

    private final WebClient aiServiceWebClient;
    private final ObjectMapper objectMapper;
    private final AgentPerformanceLogger agentPerformanceLogger;

    // ========== 강의 자료 생성 Agent (v3에서도 유지) ==========

    @Bean
    public PlanningAgent planningAgent() {
        return new PlanningAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    @Bean
    public ConfirmAgent confirmAgent() {
        return new ConfirmAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    @Bean
    public UpdateAgent updateAgent() {
        return new UpdateAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    @Bean
    public DecompositionAgent decompositionAgent() {
        return new DecompositionAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    @Bean
    public WriteAgent writeAgent() {
        return new WriteAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    @Bean
    public ValidationAgent validationAgent() {
        return new ValidationAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    @Bean
    public ReviewAgent reviewAgent() {
        return new ReviewAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    @Bean
    public EditorAgent editorAgent() {
        return new EditorAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }
}
