package io.github.uou_capstone.aiplatform.common.error;

import org.springframework.http.HttpStatus;

public interface ErrorCode {
    HttpStatus getHttpStatus(); // HTTP 상태 코드 (예: 400, 404)
    String getCode();           // 커스텀 에러 코드 (예: "1001", "4002")
    String getMessage();        // 에러 메시지
}
