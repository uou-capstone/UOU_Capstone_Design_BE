package io.github.uou_capstone.aiplatform.common.dto;

import io.github.uou_capstone.aiplatform.common.error.ErrorCode;
import lombok.Builder;
import lombok.Getter;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;

@Getter
@Builder
public class ErrorResponse {
    private final LocalDateTime timestamp = LocalDateTime.now();
    private final int status;      // HTTP 상태 코드 (예: 400)
    private final String error;    // HTTP 에러 이름 (예: BAD_REQUEST)
    private final String code;     // 커스텀 에러 코드 (예: "4000")
    private final String message;  // 상세 메시지
    private final String path;     // 요청 경로

    public static ResponseEntity<ErrorResponse> toResponseEntity(ErrorCode errorCode, String path) {
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ErrorResponse.builder()
                        .status(errorCode.getHttpStatus().value())
                        .error(errorCode.getHttpStatus().name())
                        .code(errorCode.getCode()) // 이제 ENUM 이름 대신 커스텀 코드 사용
                        .message(errorCode.getMessage())
                        .path(path)
                        .build()
                );
    }

    // 커스텀 메시지 필요 시
    public static ResponseEntity<ErrorResponse> toResponseEntity(ErrorCode errorCode, String message, String path) {
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ErrorResponse.builder()
                        .status(errorCode.getHttpStatus().value())
                        .error(errorCode.getHttpStatus().name())
                        .code(errorCode.getCode())
                        .message(message)
                        .path(path)
                        .build()
                );
    }
    
    // 일반 예외용 (커스텀 코드가 없는 경우 기본값 처리)
    public static ResponseEntity<ErrorResponse> toResponseEntity(int status, String error, String message, String path) {
        // 400번대면 1000(Common), 500번대면 5000(Server)으로 대략적인 매핑
        String defaultCode = status >= 500 ? "5000" : "1000";
        
        return ResponseEntity
                .status(status)
                .body(ErrorResponse.builder()
                        .status(status)
                        .error(error)
                        .code(defaultCode)
                        .message(message)
                        .path(path)
                        .build()
                );
    }
}
