package io.github.uou_capstone.aiplatform.util.sse;

import lombok.Builder;
import lombok.Getter;

import java.time.Duration;

/**
 * SSE 스트림 동작 정책.
 * - idleTimeout: upstream 라인이 N 동안 없으면 timeout 이벤트 emit 후 종료
 * - heartbeatPassthrough: NDJSON {"type":"heartbeat"} 라인을 SSE event:heartbeat 로 통과시킴
 * - appendDoneOnComplete: upstream 정상 완료 시 event:done 한 번 append
 */
@Getter
@Builder
public class SseStreamPolicy {

    @Builder.Default
    private final Duration idleTimeout = Duration.ofSeconds(60);

    @Builder.Default
    private final boolean heartbeatPassthrough = true;

    @Builder.Default
    private final boolean appendDoneOnComplete = true;

    public static SseStreamPolicy defaults() {
        return SseStreamPolicy.builder().build();
    }
}
