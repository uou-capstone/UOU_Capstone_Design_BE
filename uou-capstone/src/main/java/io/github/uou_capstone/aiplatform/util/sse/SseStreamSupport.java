package io.github.uou_capstone.aiplatform.util.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.util.NdjsonLineFilters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * NDJSON 또는 임의 데이터 Flux 를 표준 SSE 이벤트 스트림(message/heartbeat/timeout/error/done)으로
 * 변환하는 헬퍼.
 *
 * <p>FE 계약(2026-04):
 * <ul>
 *   <li>{@code event:done} 후 재접속 금지</li>
 *   <li>{@code event:timeout} = 서버 측 idle 종료 신호 → FE 재접속</li>
 *   <li>{@code event:error} = 사용자 표시 가능한 오류</li>
 *   <li>{@code event:heartbeat} = 연결 유지 신호 (UI 변화 없음)</li>
 * </ul>
 */
@Slf4j
public final class SseStreamSupport {

    private SseStreamSupport() {
    }

    /**
     * NDJSON 라인 Flux 를 SSE 이벤트 Flux 로 변환한다.
     */
    public static Flux<ServerSentEvent<String>> wrapNdjson(Flux<String> ndjsonLines,
                                                           ObjectMapper objectMapper,
                                                           SseStreamPolicy policy,
                                                           Function<Throwable, String> errorPayloadMapper) {
        Flux<ServerSentEvent<String>> events = ndjsonLines
                .filter(line -> line != null && !line.isBlank())
                .map(line -> NdjsonLineFilters.isHeartbeatLine(objectMapper, line)
                        ? sse(SseEventNames.HEARTBEAT, line)
                        : sse(SseEventNames.MESSAGE, line));

        if (policy.getIdleTimeout() != null && !policy.getIdleTimeout().isZero()) {
            events = events.timeout(policy.getIdleTimeout())
                    .onErrorResume(TimeoutException.class, e -> {
                        log.info("SSE idle timeout reached: {}ms", policy.getIdleTimeout().toMillis());
                        return Flux.just(sse(SseEventNames.TIMEOUT,
                                "{\"reason\":\"idle_timeout\",\"timeoutMs\":" + policy.getIdleTimeout().toMillis() + "}"));
                    });
        }

        events = events.onErrorResume(e -> {
            if (e instanceof TimeoutException) {
                return Flux.error(e);
            }
            String payload = errorPayloadMapper != null ? errorPayloadMapper.apply(e) : defaultErrorPayload(e);
            log.error("SSE upstream error", e);
            return Flux.just(sse(SseEventNames.ERROR, payload));
        });

        if (policy.isAppendDoneOnComplete()) {
            events = events.concatWith(Flux.just(sse(SseEventNames.DONE, "{}")));
        }

        return events;
    }

    /**
     * 임의 타입 SSE Flux 에 idle timeout / done append / error mapping 만 추가한다.
     * (이미 ServerSentEvent 로 변환된 스트림용)
     */
    public static <T> Flux<ServerSentEvent<T>> wrapEvents(Flux<ServerSentEvent<T>> source,
                                                          SseStreamPolicy policy,
                                                          Function<Throwable, ServerSentEvent<T>> errorMapper) {
        Flux<ServerSentEvent<T>> events = source;

        if (policy.getIdleTimeout() != null && !policy.getIdleTimeout().isZero()) {
            events = events.timeout(policy.getIdleTimeout())
                    .onErrorResume(TimeoutException.class, e -> Flux.empty());
        }

        if (errorMapper != null) {
            events = events.onErrorResume(e -> {
                if (e instanceof TimeoutException) {
                    return Flux.error(e);
                }
                log.error("SSE upstream error", e);
                return Flux.just(errorMapper.apply(e));
            });
        }

        return events;
    }

    public static <T> ServerSentEvent<T> sse(String name, T data) {
        return ServerSentEvent.<T>builder().event(name).data(data).build();
    }

    public static <T> ServerSentEvent<T> done(T payload) {
        return ServerSentEvent.<T>builder().event(SseEventNames.DONE).data(payload).build();
    }

    public static <T> ServerSentEvent<T> timeout(T payload) {
        return ServerSentEvent.<T>builder().event(SseEventNames.TIMEOUT).data(payload).build();
    }

    public static <T> ServerSentEvent<T> error(T payload) {
        return ServerSentEvent.<T>builder().event(SseEventNames.ERROR).data(payload).build();
    }

    private static String defaultErrorPayload(Throwable e) {
        String msg = e.getMessage() != null ? e.getMessage().replace("\"", "'") : "알 수 없는 오류";
        return "{\"type\":\"error\",\"message\":\"" + msg + "\"}";
    }
}
