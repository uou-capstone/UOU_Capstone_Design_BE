package io.github.uou_capstone.aiplatform.util.sse;

/**
 * SSE 이벤트 이름 표준 (FE/BE 계약, 2026-04 확정).
 *
 * <pre>
 * MESSAGE   : 일반 데이터 이벤트 (FastAPI NDJSON 라인 그대로)
 * HEARTBEAT : 연결 유지 신호 — UI 변화 없음
 * TIMEOUT   : 서버가 연결 종료를 알리는 재접속 신호
 * ERROR     : 사용자 표시 가능한 오류 이벤트
 * DONE      : 정상 완료 — 클라이언트는 재접속하지 않음
 * </pre>
 */
public final class SseEventNames {

    public static final String MESSAGE = "message";
    public static final String HEARTBEAT = "heartbeat";
    public static final String TIMEOUT = "timeout";
    public static final String ERROR = "error";
    public static final String DONE = "done";

    private SseEventNames() {
    }
}
