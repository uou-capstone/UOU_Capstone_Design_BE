package io.github.uou_capstone.aiplatform.config;

import io.github.uou_capstone.aiplatform.agent.exception.AgentExecutionException;
import io.github.uou_capstone.aiplatform.agent.exception.StreamingException;
import io.github.uou_capstone.aiplatform.common.dto.ErrorResponse;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.lecture.exception.StreamingApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;

@Slf4j
@RequiredArgsConstructor
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final ObjectMapper objectMapper;

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
        
        int statusCode = ex.getStatusCode().value();
        
        // 타임아웃 에러인 경우
        if (statusCode == 504 || statusCode == 408) {
            return ErrorResponse.toResponseEntity(
                    CommonErrorCode.AI_SERVER_TIMEOUT,
                    "AI 서비스 응답 시간이 초과되었습니다. 잠시 후 다시 시도해주세요.",
                    request.getRequestURI()
            );
        }
        
        // 503 Service Unavailable: Gemini API 일시적 부하
        if (statusCode == 503) {
            String errorBody = ex.getResponseBodyAsString();
            String message = "AI 서비스가 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요.";
            
            // 에러 본문에서 상세 메시지 추출 시도
            if (errorBody != null && errorBody.contains("high demand")) {
                message = "AI 서비스가 현재 높은 부하를 받고 있습니다. 잠시 후 다시 시도해주세요.";
            }
            
            return ErrorResponse.toResponseEntity(
                    CommonErrorCode.AI_SERVER_ERROR,
                    message,
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
     * 6-1. 지원하지 않는 HTTP 메서드 -> 405
     *
     * <p>SSE 엔드포인트는 Accept: text/event-stream 으로 요청하므로
     * Spring 기본 컨텐츠 협상이 JSON 응답을 거부(406)하는 연쇄 오류를 방지하기 위해
     * HttpServletResponse 에 직접 JSON을 기록한다.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public void handleHttpRequestMethodNotSupportedException(
            HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        log.warn("[Method Not Supported] method={}, path={}, supported={}",
                request.getMethod(), request.getRequestURI(), ex.getSupportedMethods());
        writeJsonError(response, HttpStatus.METHOD_NOT_ALLOWED,
                "지원하지 않는 HTTP 메서드입니다.", request.getRequestURI());
    }

    /**
     * 6-2. Accept 헤더 미일치 -> 406
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public void handleHttpMediaTypeNotAcceptableException(
            HttpMediaTypeNotAcceptableException ex,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        log.warn("[Not Acceptable] method={}, path={}, accept={}",
                request.getMethod(), request.getRequestURI(), request.getHeader("Accept"));
        writeJsonError(response, HttpStatus.NOT_ACCEPTABLE,
                "요청한 Accept 헤더와 응답 타입이 맞지 않습니다.", request.getRequestURI());
    }

    private void writeJsonError(HttpServletResponse response, HttpStatus status,
                                String message, String path) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ErrorResponse body = ErrorResponse.builder()
                .status(status.value())
                .error(status.name())
                .code(CommonErrorCode.INVALID_PARAMETER.getCode())
                .message(message)
                .path(path)
                .build();
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    /**
     * 6-1. 정적 리소스/매핑 없는 경로 요청 (예: GET /login) -> 404로 처리
     *
     * - 브라우저/봇이 /login, /favicon.ico 같은 경로를 두드릴 때 발생 가능
     * - 기존에는 catch-all(Exception)에서 500으로 로깅되어 운영 로그를 오염시킴
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFoundException(NoResourceFoundException ex, HttpServletRequest request) {
        log.info("[Resource Not Found] path={}", request.getRequestURI());
        return ErrorResponse.toResponseEntity(
                CommonErrorCode.RESOURCE_NOT_FOUND,
                "요청한 리소스를 찾을 수 없습니다.",
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
