package io.github.uou_capstone.aiplatform.integration.fastapi;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.util.BridgeResponseLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * FastAPI 연동 클라이언트.
 *
 * 다음 두 계열을 모두 다룬다.
 * <ul>
 *   <li>레거시 / 기존: {@code /api/v2/test-gen/generate}, {@code /api/v3/bridge/quiz[,/result]},
 *       {@code /api/v3/bridge/grade/result}</li>
 *   <li>MergeEdu 신규: {@code /bridge/discussion_assistant_stream},
 *       {@code /bridge/exam_studio/...}, {@code /bridge/report/...}</li>
 * </ul>
 *
 * 신규 {@code /bridge/*} 호출은 운영 환경에서 {@code X-AI-SECRET-KEY} 헤더가 필수다.
 * 헤더는 {@code WebClientConfig}에서 두 WebClient bean에 {@code defaultHeader}로 전역 적용된다.
 */
@Slf4j
@Component
public class FastApiBridgeClient {

    private final WebClient aiServiceWebClient;
    private final WebClient aiServiceStreamingWebClient;

    public FastApiBridgeClient(WebClient aiServiceWebClient,
                               WebClient aiServiceStreamingWebClient) {
        this.aiServiceWebClient = aiServiceWebClient;
        this.aiServiceStreamingWebClient = aiServiceStreamingWebClient;
    }

    // ===========================================================================
    // 기존 — 변경 없음
    // ===========================================================================

    /**
     * v2 시험/퀴즈 생성 (단건 JSON). Spring 동기 시험 생성({@code /api/exams/generation}) 경로에서 사용.
     */
    public String testGenGenerate(Map<String, Object> body) {
        String raw = aiServiceWebClient.post()
                .uri("/api/v2/test-gen/generate")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .onErrorMap(e -> new BusinessException(CommonErrorCode.AI_SERVER_ERROR,
                        "v2 시험 생성 서비스 호출 실패: " + e.getMessage()))
                .block();
        BridgeResponseLogger.debugBody(log, "POST /api/v2/test-gen/generate", raw);
        return raw;
    }

    /** v3 Bridge 단건 퀴즈 결과 (필요 시 별도 호출용). */
    public String quizResult(Map<String, Object> body) {
        String raw = aiServiceWebClient.post()
                .uri("/api/v3/bridge/quiz/result")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .onErrorMap(e -> new BusinessException(CommonErrorCode.AI_SERVER_ERROR,
                        "퀴즈 생성 서비스 호출 실패: " + e.getMessage()))
                .block();
        BridgeResponseLogger.debugBody(log, "POST /api/v3/bridge/quiz/result", raw);
        return raw;
    }

    public String gradeResult(Map<String, Object> body) {
        String raw = aiServiceWebClient.post()
                .uri("/api/v3/bridge/grade/result")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .onErrorMap(e -> new BusinessException(CommonErrorCode.AI_SERVER_ERROR,
                        "채점 서비스 호출 실패: " + e.getMessage()))
                .block();
        BridgeResponseLogger.debugBody(log, "POST /api/v3/bridge/grade/result", raw);
        return raw;
    }

    public Flux<String> streamQuiz(Map<String, Object> body) {
        return aiServiceWebClient.post()
                .uri("/api/v3/bridge/quiz")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class);
    }

    // ===========================================================================
    // MergeEdu 신규 /bridge/* — Spring → FastAPI 계약 (BRIDGE_AGENT_ENDPOINTS.md 기준)
    // ===========================================================================

    /** Discussion AI Assistant — NDJSON 스트림. */
    public Flux<String> discussionAssistantStream(Map<String, Object> body) {
        return aiServiceStreamingWebClient.post()
                .uri("/bridge/discussion_assistant_stream")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class);
    }

    public Flux<String> noticeAssistantStream(Map<String, Object> body) {
        return aiServiceStreamingWebClient.post()
                .uri("/bridge/notice_assistant_stream")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class);
    }

    /** Exam Studio PDF Context 발급 — 단건 JSON. */
    public String examStudioPdfContext(Map<String, Object> body) {
        String raw = aiServiceWebClient.post()
                .uri("/bridge/exam_studio/pdf_context")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .onErrorMap(e -> new BusinessException(CommonErrorCode.AI_SERVER_ERROR,
                        "Exam Studio PDF 컨텍스트 생성 실패: " + e.getMessage()))
                .block();
        BridgeResponseLogger.debugBody(log, "POST /bridge/exam_studio/pdf_context", raw);
        return raw;
    }

    /** Exam Studio Chat — NDJSON 스트림. */
    public Flux<String> examStudioChatStream(Map<String, Object> body) {
        return aiServiceStreamingWebClient.post()
                .uri("/bridge/exam_studio/chat_stream")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class);
    }

    /** Student Report Chatbot — NDJSON 스트림. 교사가 특정 학생 리포트로 follow-up 질문. */
    public Flux<String> studentReportChatStream(Map<String, Object> body) {
        return aiServiceStreamingWebClient.post()
                .uri("/bridge/report/student_chat_stream")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class);
    }

    /** Report Criteria AI 추천 — NDJSON 스트림 ({@code criterion_suggestion} 중간 이벤트 포함). */
    public Flux<String> reportCriteriaAssistantStream(Map<String, Object> body) {
        return aiServiceStreamingWebClient.post()
                .uri("/bridge/report/criteria_assistant_stream")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class);
    }

    /** Report Criteria AI Chat — NDJSON 스트림 ({@code operation} 제안). */
    public Flux<String> reportCriteriaAssistantChatStream(Map<String, Object> body) {
        return aiServiceStreamingWebClient.post()
                .uri("/bridge/report/criteria_assistant_chat_stream")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class);
    }

    /** Classroom 종합 리포트 — 단건 JSON. */
    public String reportClassroomAnalyze(Map<String, Object> body) {
        String raw = aiServiceWebClient.post()
                .uri("/bridge/report/classroom_analyze")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .onErrorMap(e -> new BusinessException(CommonErrorCode.AI_SERVER_ERROR,
                        "Classroom 리포트 분석 실패: " + e.getMessage()))
                .block();
        BridgeResponseLogger.debugBody(log, "POST /bridge/report/classroom_analyze", raw);
        return raw;
    }

    /** Classroom 종합 리포트 — NDJSON 스트림. */
    public Flux<String> reportClassroomAnalyzeStream(Map<String, Object> body) {
        return aiServiceStreamingWebClient.post()
                .uri("/bridge/report/classroom_analyze_stream")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class);
    }
}
