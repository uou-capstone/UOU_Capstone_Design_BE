package io.github.uou_capstone.aiplatform.common.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {
    
    // ==========================
    // 4xx: Client Errors
    // ==========================
    
    // 400 Bad Request
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "4000", "입력값이 올바르지 않습니다."),
    WAITING_FOR_ANSWER(HttpStatus.BAD_REQUEST, "4001", "이전 질문에 대한 답변을 기다리는 중입니다."),
    INVALID_INVITATION_CODE(HttpStatus.BAD_REQUEST, "4002", "유효하지 않은 인증 코드입니다."),
    PASSWORD_NOT_MATCH(HttpStatus.BAD_REQUEST, "4003", "비밀번호가 일치하지 않습니다."),
    INVALID_PHASE(HttpStatus.BAD_REQUEST, "4004", "잘못된 단계입니다."),

    // 401 Unauthorized
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "4010", "인증되지 않은 사용자입니다."),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "4011", "이메일 또는 비밀번호가 일치하지 않습니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "4012", "토큰이 만료되었습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "4013", "유효하지 않은 토큰입니다."),
    
    // 403 Forbidden
    FORBIDDEN(HttpStatus.FORBIDDEN, "4030", "접근 권한이 없습니다."),
    JOIN_REQUEST_BLOCKED(HttpStatus.FORBIDDEN, "4031", "해당 강의실 가입이 차단된 사용자입니다."),

    // 404 Not Found
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "4040", "요청한 리소스를 찾을 수 없습니다."),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "4041", "존재하지 않는 회원입니다."),
    COURSE_NOT_FOUND(HttpStatus.NOT_FOUND, "4042", "해당 과목을 찾을 수 없습니다."),
    LECTURE_NOT_FOUND(HttpStatus.NOT_FOUND, "4043", "해당 강의를 찾을 수 없습니다."),
    ASSESSMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "4044", "해당 평가를 찾을 수 없습니다."),
    QUESTION_NOT_FOUND(HttpStatus.NOT_FOUND, "4045", "해당 문제를 찾을 수 없습니다."),
    OPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "4046", "해당 선택지를 찾을 수 없습니다."),
    SUBMISSION_NOT_FOUND(HttpStatus.NOT_FOUND, "4047", "해당 제출 기록을 찾을 수 없습니다."),
    FILE_NOT_FOUND(HttpStatus.NOT_FOUND, "4048", "파일을 찾을 수 없습니다."),
    AI_CONTENT_NOT_FOUND(HttpStatus.NOT_FOUND, "4049", "AI 생성 콘텐츠를 찾을 수 없습니다."),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "4050", "세션을 찾을 수 없습니다."),
    DATA_NOT_FOUND(HttpStatus.NOT_FOUND, "4051", "데이터를 찾을 수 없습니다."),
    TASK_NOT_FOUND(HttpStatus.NOT_FOUND, "4052", "작업을 찾을 수 없습니다."),
    JOIN_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "4053", "가입 요청을 찾을 수 없습니다."),

    // 409 Conflict
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "4090", "이미 존재하는 이메일입니다."),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "4091", "이미 존재하는 리소스입니다."),
    OPERATION_IN_PROGRESS(HttpStatus.CONFLICT, "4092", "작업이 이미 진행 중입니다."),
    JOIN_REQUEST_PENDING_EXISTS(HttpStatus.CONFLICT, "4093", "이미 대기 중인 가입 요청이 있습니다."),
    JOIN_REQUEST_ALREADY_PROCESSED(HttpStatus.CONFLICT, "4094", "이미 처리된 가입 요청입니다."),
    ENROLLMENT_ALREADY_EXISTS(HttpStatus.CONFLICT, "4095", "이미 수강 중인 강의실입니다."),
    
    // 429 Too Many Requests
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "4290", "요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요."),
    
    // ==========================
    // 5xx: Server Errors
    // ==========================
    
    // 500 Internal Server Error
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "5000", "서버 내부 오류가 발생했습니다."),
    AI_CONTENT_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "5001", "AI 콘텐츠 생성에 실패했습니다."),
    FILE_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "5002", "파일 업로드에 실패했습니다."),
    NOT_IMPLEMENTED(HttpStatus.INTERNAL_SERVER_ERROR, "5003", "아직 구현되지 않은 기능입니다."),
    
    // Agent 관련 에러
    AGENT_EXECUTION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "5004", "Agent 실행 중 오류가 발생했습니다."),
    STREAMING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "5005", "스트리밍 중 오류가 발생했습니다."),
    
    // 502 Bad Gateway (AI Service 통신 오류 등)
    AI_SERVER_ERROR(HttpStatus.BAD_GATEWAY, "5020", "AI 서비스와 통신 중 오류가 발생했습니다."),
    
    // 504 Gateway Timeout
    AI_SERVER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "5040", "AI 서비스 응답 시간이 초과되었습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
