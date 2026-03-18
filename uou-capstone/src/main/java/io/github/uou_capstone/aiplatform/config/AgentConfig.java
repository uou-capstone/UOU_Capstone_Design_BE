package io.github.uou_capstone.aiplatform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.agent.exam.DebateGeneratorAgent;
import io.github.uou_capstone.aiplatform.agent.exam.ExamGraderAgent;
import io.github.uou_capstone.aiplatform.agent.exam.FeedbackGeneratorAgent;
import io.github.uou_capstone.aiplatform.agent.exam.FlashCardGeneratorAgent;
import io.github.uou_capstone.aiplatform.agent.exam.FiveChoiceGeneratorAgent;
import io.github.uou_capstone.aiplatform.agent.exam.OxProblemGeneratorAgent;
import io.github.uou_capstone.aiplatform.agent.exam.ShortAnswerGeneratorAgent;
import io.github.uou_capstone.aiplatform.agent.exam.debate.DebatePhase1Agent;
import io.github.uou_capstone.aiplatform.agent.exam.debate.DebaterAgent;
import io.github.uou_capstone.aiplatform.agent.exam.debate.EvaluatorAgent;
import io.github.uou_capstone.aiplatform.agent.exam.debate.EvaluationLogGeneratorAgent;
import io.github.uou_capstone.aiplatform.agent.exam.debate.ModeratorAgent;
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
 * Agent Bean 등록 Configuration
 * 
 * 목적:
 * - 모든 Agent 클래스를 Spring Bean으로 등록하여 의존성 주입 가능하게 만듭니다.
 * - Service 레이어에서 Agent를 주입받아 사용할 수 있도록 합니다.
 * 
 * 등록되는 Agent:
 * - 강의 자료 생성 Agent: PlanningAgent, ConfirmAgent
 * - 시험 생성 Agent: ProfileAgent, FlashCardGeneratorAgent, OxProblemGeneratorAgent, ExamGraderAgent
 * 
 * 의존성:
 * - WebClient: FastAPI AI 서비스와 통신하기 위한 WebClient (WebClientConfig에서 등록됨)
 * - ObjectMapper: JSON 변환을 위한 ObjectMapper (Spring Boot에서 자동 등록됨)
 */
@Configuration
@RequiredArgsConstructor
public class AgentConfig {

    // ========== 의존성 주입 ==========
    // WebClient는 WebClientConfig에서 이미 Bean으로 등록되어 있음
    private final WebClient aiServiceWebClient;
    
    // ObjectMapper는 Spring Boot에서 자동으로 Bean으로 등록됨
    private final ObjectMapper objectMapper;
    
    // AgentPerformanceLogger는 성능 로깅을 위해 필요
    private final AgentPerformanceLogger agentPerformanceLogger;

    // ========== 강의 자료 생성 Agent ==========

    /**
     * PlanningAgent Bean 등록
     * 
     * 역할: Phase 1에서 키워드 분석 및 DraftPlan 생성
     * 엔드포인트: /api/lecture-gen/phase1/planning
     * 
     * 사용 위치: MaterialGenerationService.startPhase1()
     */
    @Bean
    public PlanningAgent planningAgent() {
        return new PlanningAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    /**
     * ConfirmAgent Bean 등록
     * 
     * 역할: Phase 2에서 사용자 피드백 분석 (승인/수정 판단)
     * 엔드포인트: /api/lecture-gen/phase2/confirm
     * 
     * 사용 위치: MaterialGenerationService.processPhase2()
     */
    @Bean
    public ConfirmAgent confirmAgent() {
        return new ConfirmAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    /**
     * UpdateAgent Bean 등록
     * 
     * 역할: Phase 2에서 사용자 피드백 기반 DraftPlan 수정
     * 엔드포인트: /api/lecture-gen/phase2/update
     * 
     * 사용 위치: MaterialGenerationService.processPhase2() (feedback 처리)
     */
    @Bean
    public UpdateAgent updateAgent() {
        return new UpdateAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    // ========== 시험 생성 Agent ==========

    /**
     * FlashCardGeneratorAgent Bean 등록
     * 
     * 역할: 플래시카드 생성
     * 엔드포인트: /api/test-gen/flash-card
     * 
     * 사용 위치: ExamGenerationService.generateExam() (FLASH_CARD 유형)
     */
    @Bean
    public FlashCardGeneratorAgent flashCardGeneratorAgent() {
        return new FlashCardGeneratorAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    /**
     * OxProblemGeneratorAgent Bean 등록
     * 
     * 역할: OX 문제 생성
     * 엔드포인트: /api/test-gen/ox-problem
     * 
     * 사용 위치: ExamGenerationService.generateExam() (OX_PROBLEM 유형)
     */
    @Bean
    public OxProblemGeneratorAgent oxProblemGeneratorAgent() {
        return new OxProblemGeneratorAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    /**
     * ExamGraderAgent Bean 등록
     * 
     * 역할: 시험 채점 (시험 유형별로 분기)
     * 엔드포인트: /api/test-gen/grade
     * 
     * 사용 위치: ExamGradingService
     */
    @Bean
    public ExamGraderAgent examGraderAgent() {
        return new ExamGraderAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    /**
     * DecompositionAgent Bean 등록
     * 
     * 역할: Phase 3에서 챕터를 하위 주제로 분해
     * 엔드포인트: /api/lecture-gen/phase3/decomposition
     * 
     * 사용 위치: MaterialGenerationService.processPhase3()
     */
    @Bean
    public DecompositionAgent decompositionAgent() {
        return new DecompositionAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    /**
     * WriteAgent Bean 등록
     * 
     * 역할: Phase 3에서 Markdown 본문 작성
     * 엔드포인트: /api/lecture-gen/phase3/write
     * 
     * 사용 위치: MaterialGenerationService.processPhase3()
     */
    @Bean
    public WriteAgent writeAgent() {
        return new WriteAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    /**
     * ValidationAgent Bean 등록
     * 
     * 역할: Phase 4에서 검색 결과 충분성 검증
     * 엔드포인트: /api/lecture-gen/phase4/validation
     * 
     * 사용 위치: MaterialGenerationService.processPhase4()
     */
    @Bean
    public ValidationAgent validationAgent() {
        return new ValidationAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    /**
     * ReviewAgent Bean 등록
     * 
     * 역할: Phase 4에서 품질 검증
     * 엔드포인트: /api/lecture-gen/phase4/review
     * 
     * 사용 위치: MaterialGenerationService.processPhase4()
     */
    @Bean
    public ReviewAgent reviewAgent() {
        return new ReviewAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    /**
     * EditorAgent Bean 등록
     * 
     * 역할: Phase 5에서 최종 조립 및 수정 요청 반영
     * 엔드포인트: /api/lecture-gen/phase5/editor
     * 
     * 사용 위치: MaterialGenerationService.processPhase5()
     */
    @Bean
    public EditorAgent editorAgent() {
        return new EditorAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    /**
     * FiveChoiceGeneratorAgent Bean 등록
     * 
     * 역할: 5지선다 문제 생성
     * 엔드포인트: /api/test-gen/five-choice
     * 
     * 사용 위치: ExamGenerationService.generateExam() (FIVE_CHOICE 유형)
     */
    @Bean
    public FiveChoiceGeneratorAgent fiveChoiceGeneratorAgent() {
        return new FiveChoiceGeneratorAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    /**
     * ShortAnswerGeneratorAgent Bean 등록
     * 
     * 역할: 단답형/서술형 문제 생성
     * 엔드포인트: /api/test-gen/short-answer
     * 
     * 사용 위치: ExamGenerationService.generateExam() (SHORT_ANSWER 유형)
     */
    @Bean
    public ShortAnswerGeneratorAgent shortAnswerGeneratorAgent() {
        return new ShortAnswerGeneratorAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    /**
     * DebateGeneratorAgent Bean 등록
     * 
     * 역할: 토론형 문제 생성
     * 엔드포인트: /api/test-gen/debate
     * 
     * 사용 위치: ExamGenerationService.generateExam() (DEBATE 유형)
     */
    @Bean
    public DebateGeneratorAgent debateGeneratorAgent() {
        return new DebateGeneratorAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    /**
     * FeedbackGeneratorAgent Bean 등록
     * 
     * 역할: 시험 응시 후 사용자 피드백 프로필 생성
     * 엔드포인트: /api/test-gen/feedback
     * 
     * 사용 위치: ExamSubmissionService.submitExam() (시험 응시 후)
     */
    @Bean
    public FeedbackGeneratorAgent feedbackGeneratorAgent() {
        return new FeedbackGeneratorAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    // ========== 토론형 시험 상호작용 Agent ==========

    /**
     * DebatePhase1Agent Bean 등록
     * 
     * 역할: 토론형 시험 Phase 1 - 모드 설정 및 주제 선정
     * 엔드포인트: /api/test-gen/debate/phase1
     * 
     * 사용 위치: DebateService.startDebate()
     */
    @Bean
    public DebatePhase1Agent debatePhase1Agent() {
        return new DebatePhase1Agent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    /**
     * ModeratorAgent Bean 등록
     * 
     * 역할: 토론형 시험 Phase 2 - 사회자 역할 (사용자 입력 검증)
     * 엔드포인트: /api/test-gen/debate/moderator
     * 
     * 사용 위치: DebateService.respondToDebate()
     */
    @Bean
    public ModeratorAgent moderatorAgent() {
        return new ModeratorAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    /**
     * DebaterAgent Bean 등록
     * 
     * 역할: 토론형 시험 Phase 2 - 사용자 입력에 대한 반박 생성
     * 엔드포인트: /api/test-gen/debate/debater
     * 
     * 사용 위치: DebateService.respondToDebate()
     */
    @Bean
    public DebaterAgent debaterAgent() {
        return new DebaterAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    /**
     * EvaluatorAgent Bean 등록
     * 
     * 역할: 토론형 시험 Phase 2 - 사용자 입력의 논리적 품질 평가
     * 엔드포인트: /api/test-gen/debate/evaluator
     * 
     * 사용 위치: DebateService.respondToDebate()
     */
    @Bean
    public EvaluatorAgent evaluatorAgent() {
        return new EvaluatorAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }

    /**
     * EvaluationLogGeneratorAgent Bean 등록
     * 
     * 역할: 토론형 시험 Phase 3 - 토론 로그 데이터 생성
     * 엔드포인트: /api/test-gen/debate/evaluation-log
     * 
     * 사용 위치: DebateService.generateEvaluationLog()
     */
    @Bean
    public EvaluationLogGeneratorAgent evaluationLogGeneratorAgent() {
        return new EvaluationLogGeneratorAgent(aiServiceWebClient, objectMapper, agentPerformanceLogger);
    }
}
