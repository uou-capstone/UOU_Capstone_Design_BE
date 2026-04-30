package io.github.uou_capstone.aiplatform.domain.notification.service;

import io.github.uou_capstone.aiplatform.domain.notification.dto.NotificationItemDto;
import io.github.uou_capstone.aiplatform.util.sse.SseEventNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 사용자별 SSE sink 레지스트리. 한 사용자가 여러 탭/디바이스로 접속할 수 있으므로
 * userId -> sink list 구조다. 단일 인스턴스 in-memory 한정 (멀티 인스턴스 운영 시
 * Redis Pub/Sub 브로드캐스트 도입 필요).
 */
@Slf4j
@Component
public class NotificationStreamRegistry {

    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<Sinks.Many<ServerSentEvent<Map<String, Object>>>>>
            sinksByUser = new ConcurrentHashMap<>();

    public Flux<ServerSentEvent<Map<String, Object>>> register(Long userId) {
        Sinks.Many<ServerSentEvent<Map<String, Object>>> sink = Sinks.many().multicast().onBackpressureBuffer();
        sinksByUser
                .computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>())
                .add(sink);

        return sink.asFlux()
                .doFinally(signal -> remove(userId, sink));
    }

    public void push(Long userId, NotificationItemDto dto) {
        CopyOnWriteArrayList<Sinks.Many<ServerSentEvent<Map<String, Object>>>> list = sinksByUser.get(userId);
        if (list == null || list.isEmpty()) {
            return;
        }
        ServerSentEvent<Map<String, Object>> event = ServerSentEvent.<Map<String, Object>>builder()
                .event(SseEventNames.MESSAGE)
                .data(dto.toSsePayload())
                .build();
        for (Sinks.Many<ServerSentEvent<Map<String, Object>>> sink : list) {
            Sinks.EmitResult result = sink.tryEmitNext(event);
            if (result.isFailure()) {
                log.debug("notification sink emit failure userId={} result={}", userId, result);
            }
        }
    }

    private void remove(Long userId, Sinks.Many<ServerSentEvent<Map<String, Object>>> sink) {
        CopyOnWriteArrayList<Sinks.Many<ServerSentEvent<Map<String, Object>>>> list = sinksByUser.get(userId);
        if (list == null) {
            return;
        }
        list.remove(sink);
        if (list.isEmpty()) {
            sinksByUser.remove(userId, list);
        }
    }
}
