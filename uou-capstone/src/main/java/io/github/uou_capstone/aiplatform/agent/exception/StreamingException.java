package io.github.uou_capstone.aiplatform.agent.exception;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;

/**
 * 스트리밍 예외
 * Agent 스트리밍 중 발생하는 예외를 처리
 */
public class StreamingException extends BusinessException {
    
    public StreamingException(String message) {
        super(CommonErrorCode.STREAMING_FAILED, message);
    }
    
    public StreamingException(String message, Throwable cause) {
        super(CommonErrorCode.STREAMING_FAILED, message);
        initCause(cause);
    }
}
