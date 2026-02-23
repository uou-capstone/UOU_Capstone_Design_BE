package io.github.uou_capstone.aiplatform.agent.exception;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;

/**
 * Agent 실행 예외
 * AI Agent 실행 중 발생하는 예외를 처리
 */
public class AgentExecutionException extends BusinessException {
    
    public AgentExecutionException(String message) {
        super(CommonErrorCode.AGENT_EXECUTION_FAILED, message);
    }
    
    public AgentExecutionException(String message, Throwable cause) {
        super(CommonErrorCode.AGENT_EXECUTION_FAILED, message);
        initCause(cause);
    }
}
