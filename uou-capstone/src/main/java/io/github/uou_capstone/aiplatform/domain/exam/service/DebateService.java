package io.github.uou_capstone.aiplatform.domain.exam.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.agent.AgentRequest;
import io.github.uou_capstone.aiplatform.agent.exam.debate.DebatePhase1Agent;
import io.github.uou_capstone.aiplatform.agent.exam.debate.DebaterAgent;
import io.github.uou_capstone.aiplatform.agent.exam.debate.EvaluatorAgent;
import io.github.uou_capstone.aiplatform.agent.exam.debate.EvaluationLogGeneratorAgent;
import io.github.uou_capstone.aiplatform.agent.exam.debate.ModeratorAgent;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.LectureRepository;
import io.github.uou_capstone.aiplatform.domain.exam.dto.*;
import io.github.uou_capstone.aiplatform.domain.exam.entity.DebatePhase;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamStatus;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamSessionRepository;
import io.github.uou_capstone.aiplatform.domain.material.repository.MaterialRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 토론형 시험 상호작용 서비스
 * Phase 1: 모드 설정 및 주제 선정
 * Phase 2: 경쟁 루프 (사용자 입력 → AI 반박)
 * Phase 3: 로그 데이터 생성
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DebateService {

    private final ExamSessionRepository examSessionRepository;
    private final LectureRepository lectureRepository;
    private final MaterialRepository materialRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    // 토론형 Agent들
    private final DebatePhase1Agent debatePhase1Agent;
    private final ModeratorAgent moderatorAgent;
    private final DebaterAgent debaterAgent;
    private final EvaluatorAgent evaluatorAgent;
    private final EvaluationLogGeneratorAgent evaluationLogGeneratorAgent;

    /**
     * Phase 1: 토론형 시험 시작 (모드 설정 및 주제 선정)
     */
    @Transactional
    public DebateStartResponseDto startDebate(DebateStartRequestDto request, Long userId) {
        ExamSession session = examSessionRepository.findById(request.getExamSessionId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "시험 세션을 찾을 수 없습니다."));

        // 토론형 시험인지 확인
        if (session.getExamType() != ExamType.DEBATE) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "토론형 시험이 아닙니다.");
        }

        // 권한 확인
        if (!session.getUser().getId().equals(userId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "권한이 없습니다.");
        }

        // Phase 1 Agent 호출
        Map<String, Object> context = new HashMap<>();
        context.put("exam_session_id", session.getId());
        context.put("lecture_id", session.getLecture().getId());
        context.put("mode", request.getMode() != null ? request.getMode() : "debate");
        if (request.getTopic() != null) {
            context.put("user_topic", request.getTopic());
        }

        // 강의 자료 내용 가져오기
        Lecture lecture = session.getLecture();
        String lectureContent = "";
        
        // Material에서 PDF 또는 생성된 강의 자료 조회
        var pdfMaterial = materialRepository
                .findFirstByLecture_IdAndMaterialTypeOrderByCreatedAtDesc(
                        lecture.getId(), 
                        "PDF"
                )
                .orElse(null);
        
        if (pdfMaterial != null) {
            String pdfPath = pdfMaterial.getFilePath();
            String pdfText = io.github.uou_capstone.aiplatform.util.PdfTextExtractor.extractTextIfLocal(pdfPath);
            lectureContent = (pdfText != null && !pdfText.trim().isEmpty()) ? pdfText : pdfPath;
        } else if (lecture.getDescription() != null) {
            lectureContent = lecture.getDescription();
        }

        context.put("lecture_content", lectureContent);
        if (session.getPriorProfileJson() != null) {
            context.put("profile", session.getPriorProfileJson());
        }

        AgentRequest agentRequest = new SimpleAgentRequest("Start debate session", context);
        @SuppressWarnings("unchecked")
        Map<String, Object> phase1Result = (Map<String, Object>) debatePhase1Agent.execute(agentRequest, Map.class);

        // 세션 업데이트
        session.updateDebatePhase(DebatePhase.PHASE1);
        if (phase1Result.containsKey("topic")) {
            Map<String, Object> examContent = new HashMap<>();
            examContent.put("topic", phase1Result.get("topic"));
            examContent.put("settings", phase1Result.get("settings"));
            session.updateExamContent(examContent);
        }

        examSessionRepository.save(session);

        // 응답 생성
        DebateStartResponseDto response = new DebateStartResponseDto();
        response.setExamSessionId(session.getId());
        response.setPhase("PHASE1");
        response.setTopic((String) phase1Result.get("topic"));
        response.setSettings((Map<String, Object>) phase1Result.get("settings"));
        response.setMessage("토론 세션이 시작되었습니다.");

        return response;
    }

    /**
     * Phase 2: 토론 응답 (사용자 입력 → AI 반박)
     */
    @Transactional
    public DebateRespondResponseDto respondToDebate(DebateRespondRequestDto request, Long userId) {
        ExamSession session = examSessionRepository.findById(request.getExamSessionId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "시험 세션을 찾을 수 없습니다."));

        // 토론형 시험인지 확인
        if (session.getExamType() != ExamType.DEBATE) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "토론형 시험이 아닙니다.");
        }

        // 권한 확인
        if (!session.getUser().getId().equals(userId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "권한이 없습니다.");
        }

        // Phase 2로 전환
        if (session.getDebatePhase() == null || session.getDebatePhase() != DebatePhase.PHASE2) {
            session.updateDebatePhase(DebatePhase.PHASE2);
        }

        // 기존 토론 기록 가져오기
        Map<String, Object> debateHistory = session.getDebateHistoryJson() != null 
                ? new HashMap<>(session.getDebateHistoryJson()) 
                : new HashMap<>();

        // Moderator Agent: 사용자 입력 검증
        Map<String, Object> moderatorContext = new HashMap<>();
        moderatorContext.put("user_input", request.getUserInput());
        moderatorContext.put("debate_history", debateHistory);
        moderatorContext.put("exam_content", session.getExamContentJson());
        if (session.getPriorProfileJson() != null) {
            moderatorContext.put("profile", session.getPriorProfileJson());
        }

        AgentRequest moderatorRequest = new SimpleAgentRequest("Validate user input", moderatorContext);
        @SuppressWarnings("unchecked")
        Map<String, Object> moderatorResult = (Map<String, Object>) moderatorAgent.execute(moderatorRequest, Map.class);

        // Evaluator Agent: 사용자 입력 평가
        Map<String, Object> evaluatorContext = new HashMap<>();
        evaluatorContext.put("user_input", request.getUserInput());
        evaluatorContext.put("debate_history", debateHistory);
        evaluatorContext.put("exam_content", session.getExamContentJson());

        AgentRequest evaluatorRequest = new SimpleAgentRequest("Evaluate user input", evaluatorContext);
        @SuppressWarnings("unchecked")
        Map<String, Object> evaluationResult = (Map<String, Object>) evaluatorAgent.execute(evaluatorRequest, Map.class);

        // Debater Agent: 반박 생성
        Map<String, Object> debaterContext = new HashMap<>();
        debaterContext.put("user_input", request.getUserInput());
        debaterContext.put("debate_history", debateHistory);
        debaterContext.put("exam_content", session.getExamContentJson());
        debaterContext.put("evaluation", evaluationResult);

        AgentRequest debaterRequest = new SimpleAgentRequest("Generate rebuttal", debaterContext);
        @SuppressWarnings("unchecked")
        Map<String, Object> debaterResult = (Map<String, Object>) debaterAgent.execute(debaterRequest, Map.class);

        // 토론 기록 업데이트
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conversationHistory = (List<Map<String, Object>>) debateHistory.getOrDefault("conversations", new java.util.ArrayList<>());
        
        Map<String, Object> userTurn = new HashMap<>();
        userTurn.put("role", "user");
        userTurn.put("content", request.getUserInput());
        userTurn.put("evaluation", evaluationResult);
        conversationHistory.add(userTurn);

        Map<String, Object> aiTurn = new HashMap<>();
        aiTurn.put("role", "debater");
        aiTurn.put("content", debaterResult.get("rebuttal"));
        conversationHistory.add(aiTurn);

        debateHistory.put("conversations", conversationHistory);
        debateHistory.put("current_score", evaluationResult.get("score"));
        debateHistory.put("round", conversationHistory.size() / 2);

        session.updateDebateHistory(debateHistory);
        examSessionRepository.save(session);

        // 토론 종료 조건 확인 (예: 최대 라운드 수 또는 점수 기준)
        Boolean isCompleted = false;
        Integer score = evaluationResult.get("score") != null ? (Integer) evaluationResult.get("score") : 0;
        Integer maxRounds = 10; // 최대 라운드 수 (설정 가능)
        
        if (conversationHistory.size() / 2 >= maxRounds || score >= 100) {
            isCompleted = true;
            session.updateDebatePhase(DebatePhase.PHASE3);
            // Phase 3: 로그 생성
            generateEvaluationLog(session);
        }

        // 응답 생성
        DebateRespondResponseDto response = new DebateRespondResponseDto();
        response.setExamSessionId(session.getId());
        response.setPhase("PHASE2");
        response.setDebaterResponse((String) debaterResult.get("rebuttal"));
        response.setScore(score);
        response.setEvaluation(evaluationResult);
        response.setIsCompleted(isCompleted);
        response.setMessage(isCompleted ? "토론이 종료되었습니다." : "토론이 계속됩니다.");

        return response;
    }

    /**
     * Phase 3: 평가 로그 생성
     */
    @Transactional
    public void generateEvaluationLog(ExamSession session) {
        if (session.getDebateHistoryJson() == null) {
            log.warn("토론 기록이 없어 로그를 생성할 수 없습니다. sessionId: {}", session.getId());
            return;
        }

        Map<String, Object> context = new HashMap<>();
        context.put("debate_history", session.getDebateHistoryJson());
        context.put("exam_content", session.getExamContentJson());
        if (session.getPriorProfileJson() != null) {
            context.put("profile", session.getPriorProfileJson());
        }

        AgentRequest request = new SimpleAgentRequest("Generate evaluation log", context);
        @SuppressWarnings("unchecked")
        Map<String, Object> evaluationLog = (Map<String, Object>) evaluationLogGeneratorAgent.execute(request, Map.class);

        session.updateEvaluationLog(evaluationLog);
        session.updateStatus(ExamStatus.COMPLETED);
        examSessionRepository.save(session);
    }

    // Simple AgentRequest 구현
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
