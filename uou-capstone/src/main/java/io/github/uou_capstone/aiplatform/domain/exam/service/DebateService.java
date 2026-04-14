package io.github.uou_capstone.aiplatform.domain.exam.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.exam.dto.*;
import io.github.uou_capstone.aiplatform.domain.exam.entity.DebatePhase;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamStatus;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamSessionRepository;
import io.github.uou_capstone.aiplatform.integration.fastapi.FastApiSessionClient;
import io.github.uou_capstone.aiplatform.domain.material.repository.MaterialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 토론형 시험 상호작용 서비스 (v3)
 *
 * Moderator/Evaluator/Debater 에이전트 순차 호출을 제거하고
 * FastAPI 세션 이벤트 단건 위임으로 교체한다.
 * 종료 조건 판단(maxRounds, score)도 FastAPI가 담당한다.
 *
 * FastAPI 세션 ID 규칙: "debate-{examSessionId}"
 *
 * MySQL ExamSession은 메타 정보(phase, history, status) 기록 용도로 유지한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DebateService {

    private final ExamSessionRepository examSessionRepository;
    private final MaterialRepository materialRepository;
    private final FastApiSessionClient fastApiSessionClient;

    /**
     * Phase 1: 토론형 시험 시작
     *
     * FastAPI 세션에 DEBATE_STARTED 이벤트를 전송하여 주제 및 설정을 생성한다.
     */
    @Transactional
    public DebateStartResponseDto startDebate(DebateStartRequestDto request, Long userId) {
        ExamSession session = examSessionRepository.findById(request.getExamSessionId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND,
                        "시험 세션을 찾을 수 없습니다."));

        if (session.getExamType() != ExamType.DEBATE) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "토론형 시험이 아닙니다.");
        }
        if (!session.getUser().getId().equals(userId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "권한이 없습니다.");
        }

        String lectureContent = resolveLectureContent(session);
        String fastApiSessionId = toFastApiSessionId(session.getId());

        // FastAPI EventRequest: type + payload (토론 전용 타입은 FastAPI AppEventType에 있어야 함)
        Map<String, Object> payload = new HashMap<>();
        payload.put("mode", request.getMode() != null ? request.getMode() : "debate");
        payload.put("lecture_content", lectureContent);
        payload.put("exam_content", session.getExamContentJson());
        if (request.getTopic() != null) {
            payload.put("user_topic", request.getTopic());
        }
        if (session.getPriorProfileJson() != null) {
            payload.put("profile", session.getPriorProfileJson());
        }
        Map<String, Object> eventBody = new HashMap<>();
        eventBody.put("type", "DEBATE_STARTED");
        eventBody.put("payload", payload);

        Map<String, Object> phase1Result = callSessionEvent(fastApiSessionId, eventBody);

        session.updateDebatePhase(DebatePhase.PHASE1);
        if (phase1Result.containsKey("topic")) {
            Map<String, Object> examContent = new HashMap<>();
            examContent.put("topic", phase1Result.get("topic"));
            examContent.put("settings", phase1Result.get("settings"));
            session.updateExamContent(examContent);
        }
        examSessionRepository.save(session);

        DebateStartResponseDto response = new DebateStartResponseDto();
        response.setExamSessionId(session.getId());
        response.setPhase("PHASE1");
        response.setTopic((String) phase1Result.get("topic"));
        response.setSettings((Map<String, Object>) phase1Result.get("settings"));
        response.setMessage("토론 세션이 시작되었습니다.");
        return response;
    }

    /**
     * Phase 2: 토론 응답
     *
     * 사용자 입력을 FastAPI 세션 USER_MESSAGE 이벤트로 위임한다.
     * 종료 조건 판단은 FastAPI 응답의 is_completed 필드로 결정한다.
     */
    @Transactional
    public DebateRespondResponseDto respondToDebate(DebateRespondRequestDto request, Long userId) {
        ExamSession session = examSessionRepository.findById(request.getExamSessionId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND,
                        "시험 세션을 찾을 수 없습니다."));

        if (session.getExamType() != ExamType.DEBATE) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "토론형 시험이 아닙니다.");
        }
        if (!session.getUser().getId().equals(userId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "권한이 없습니다.");
        }

        if (session.getDebatePhase() != DebatePhase.PHASE2) {
            session.updateDebatePhase(DebatePhase.PHASE2);
        }

        String fastApiSessionId = toFastApiSessionId(session.getId());

        // FastAPI EventRequest: type + payload (루트에 text 두면 Pydantic이 무시함)
        Map<String, Object> payload = new HashMap<>();
        payload.put("question", request.getUserInput());
        if (session.getDebateHistoryJson() != null) {
            payload.put("debate_history", session.getDebateHistoryJson());
        }
        Map<String, Object> eventBody = new HashMap<>();
        eventBody.put("type", "USER_MESSAGE");
        eventBody.put("payload", payload);

        Map<String, Object> result = callSessionEvent(fastApiSessionId, eventBody);

        // 토론 기록 업데이트
        Map<String, Object> debateHistory = session.getDebateHistoryJson() != null
                ? new HashMap<>(session.getDebateHistoryJson())
                : new HashMap<>();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conversations = (List<Map<String, Object>>)
                debateHistory.getOrDefault("conversations", new ArrayList<>());

        Map<String, Object> userTurn = new HashMap<>();
        userTurn.put("role", "user");
        userTurn.put("content", request.getUserInput());
        conversations.add(userTurn);

        Map<String, Object> aiTurn = new HashMap<>();
        aiTurn.put("role", "debater");
        aiTurn.put("content", result.get("debater_response"));
        conversations.add(aiTurn);

        debateHistory.put("conversations", conversations);
        debateHistory.put("current_score", result.get("score"));
        debateHistory.put("round", conversations.size() / 2);
        session.updateDebateHistory(debateHistory);

        Boolean isCompleted = Boolean.TRUE.equals(result.get("is_completed"));
        if (isCompleted) {
            session.updateDebatePhase(DebatePhase.PHASE3);
            session.updateStatus(ExamStatus.COMPLETED);
        }
        examSessionRepository.save(session);

        Integer score = result.get("score") instanceof Number n ? n.intValue() : 0;

        DebateRespondResponseDto response = new DebateRespondResponseDto();
        response.setExamSessionId(session.getId());
        response.setPhase(isCompleted ? "PHASE3" : "PHASE2");
        response.setDebaterResponse((String) result.get("debater_response"));
        response.setScore(score);
        response.setEvaluation((Map<String, Object>) result.get("evaluation"));
        response.setIsCompleted(isCompleted);
        response.setMessage(isCompleted ? "토론이 종료되었습니다." : "토론이 계속됩니다.");
        return response;
    }

    // ──────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────

    /**
     * FastAPI 세션 ID 생성 규칙.
     * MySQL ExamSession ID를 기반으로 일관된 FastAPI 세션 ID를 결정한다.
     */
    private String toFastApiSessionId(Long examSessionId) {
        return "debate-" + examSessionId;
    }

    /**
     * 강의 자료 텍스트를 결정한다.
     * PDF가 있으면 텍스트 추출, 없으면 강의 설명 사용.
     */
    private String resolveLectureContent(ExamSession session) {
        var pdfMaterial = session.getMaterial();
        if (pdfMaterial == null) {
            pdfMaterial = materialRepository
                    .findFirstByLecture_IdAndMaterialTypeOrderByCreatedAtDesc(
                            session.getLecture().getId(), "PDF")
                    .orElse(null);
        }

        if (pdfMaterial != null) {
            String pdfText = io.github.uou_capstone.aiplatform.util.PdfTextExtractor
                    .extractTextIfLocal(pdfMaterial.getFilePath());
            if (pdfText != null && !pdfText.isBlank()) return pdfText;
            return pdfMaterial.getFilePath();
        }

        String desc = session.getLecture().getDescription();
        return desc != null ? desc : "";
    }

    /**
     * FastAPI POST /api/v3/session/{sessionId}/event 단건(비스트리밍) 호출.
     *
     * @param sessionId  FastAPI 세션 ID
     * @param eventBody  이벤트 바디 (type 포함)
     * @return FastAPI 응답 Map
     */
    private Map<String, Object> callSessionEvent(String sessionId, Map<String, Object> eventBody) {
        return fastApiSessionClient.callEvent(sessionId, eventBody);
    }
}
