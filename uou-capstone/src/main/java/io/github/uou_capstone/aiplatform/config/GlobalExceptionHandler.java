package io.github.uou_capstone.aiplatform.config;

import io.github.uou_capstone.aiplatform.agent.exception.AgentExecutionException;
import io.github.uou_capstone.aiplatform.agent.exception.StreamingException;
import io.github.uou_capstone.aiplatform.common.dto.ErrorResponse;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.lecture.exception.StreamingApiException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 1. BusinessException: 커스텀 에러 (ErrorCode 사용)
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException ex, HttpServletRequest request) {
        log.warn("[Business Exception] code={}, message={}", ex.getErrorCode().getCode(), ex.getMessage());
        return ErrorResponse.toResponseEntity(ex.getErrorCode(), ex.getMessage(), request.getRequestURI());
    }

    /**
     * 2. StreamingApiException: 기존 AI 에러 (호환성) -> 4000번대(AI)로 매핑
     */
    @ExceptionHandler(StreamingApiException.class)
    public ResponseEntity<ErrorResponse> handleStreamingApiException(StreamingApiException ex, HttpServletRequest request) {
        log.warn("[Streaming API Error] status={}, message={}", ex.getStatusCode(), ex.getMessage());
        
        return ResponseEntity
                .status(ex.getStatusCode())
                .body(ErrorResponse.builder()
                        .status(ex.getStatusCode().value())
                        .error(HttpStatus.valueOf(ex.getStatusCode().value()).name())
                        .code("4000") // AI 관련 에러 통일
                        .message(ex.getMessage())
                        .path(request.getRequestURI())
                        .build()
                );
    }

    /**
     * 3. WebClient 요청 실패 -> 5020번 (AI 통신 오류)
     */
    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ErrorResponse> handleWebClientResponseException(WebClientResponseException ex, HttpServletRequest request) {
        log.error("[WebClient Error] status={}, body={}", ex.getStatusCode(), ex.getResponseBodyAsString());
        
        // 타임아웃 에러인 경우
        if (ex.getStatusCode().value() == 504 || ex.getStatusCode().value() == 408) {
            return ErrorResponse.toResponseEntity(
                    CommonErrorCode.AI_SERVER_TIMEOUT,
                    "AI 서비스 응답 시간이 초과되었습니다. 잠시 후 다시 시도해주세요.",
                    request.getRequestURI()
            );
        }
        
        return ErrorResponse.toResponseEntity(
                CommonErrorCode.AI_SERVER_ERROR,
                "AI 서비스 통신 오류: " + ex.getStatusText(),
                request.getRequestURI()
        );
    }

    /**
     * 4. Validation 실패 -> 4000번 (공통 파라미터 오류)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String errorMessage = ex.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        log.warn("[Validation Error] {}", errorMessage);
        return ErrorResponse.toResponseEntity(
                CommonErrorCode.INVALID_PARAMETER, // "4000"
                errorMessage,
                request.getRequestURI()
        );
    }

    /**
     * 5. JSON 파싱 실패 (잘못된 형식) -> 4000번
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.warn("[JSON Parse Error] {}", ex.getMessage());
        return ErrorResponse.toResponseEntity(
                CommonErrorCode.INVALID_PARAMETER, // "4000"
                "요청한 JSON 본문을 파싱할 수 없습니다. 형식을 확인해주세요.",
                request.getRequestURI()
        );
    }

    /**
     * 6. 필수 파라미터 누락 -> 4000번
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingServletRequestParameterException(MissingServletRequestParameterException ex, HttpServletRequest request) {
        log.warn("[Missing Parameter] parameterName={}, parameterType={}", ex.getParameterName(), ex.getParameterType());
        return ErrorResponse.toResponseEntity(
                CommonErrorCode.INVALID_PARAMETER,
                String.format("필수 파라미터 '%s'가 누락되었습니다.", ex.getParameterName()),
                request.getRequestURI()
        );
    }

    /**
     * 7. AgentExecutionException: Agent 실행 실패
     */
    @ExceptionHandler(AgentExecutionException.class)
    public ResponseEntity<ErrorResponse> handleAgentExecutionException(AgentExecutionException ex, HttpServletRequest request) {
        log.error("[Agent Execution Error] ", ex);
        return ErrorResponse.toResponseEntity(
                CommonErrorCode.AGENT_EXECUTION_FAILED,
                ex.getMessage(),
                request.getRequestURI()
        );
    }

    /**
     * 8. StreamingException: 스트리밍 실패
     */
    @ExceptionHandler(StreamingException.class)
    public ResponseEntity<ErrorResponse> handleStreamingException(StreamingException ex, HttpServletRequest request) {
        log.error("[Streaming Error] ", ex);
        return ErrorResponse.toResponseEntity(
                CommonErrorCode.STREAMING_FAILED,
                ex.getMessage(),
                request.getRequestURI()
        );
    }

    /**
     * 9. 나머지 서버 에러 -> 5000번 (서버 오류)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAllExceptions(Exception ex, HttpServletRequest request) {
        log.error("[Internal Server Error] ", ex);
        return ErrorResponse.toResponseEntity(
                CommonErrorCode.INTERNAL_SERVER_ERROR, // "5000"
                "서버 내부 오류가 발생했습니다. 관리자에게 문의하세요.",
                request.getRequestURI()
        );
    }
}
