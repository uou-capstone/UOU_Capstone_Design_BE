package io.github.uou_capstone.aiplatform.domain.material.generation.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Material Generation 진행 상황 리스너
 * 
 * Redis Pub/Sub을 통해 받은 진행 상황 메시지를 SSE로 클라이언트에게 전달합니다.
 * 
 * 채널 형식: "progress:session:{sessionId}"
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MaterialGenerationProgressListener implements MessageListener {

    private final ObjectMapper objectMapper;
    
    // 활성 SSE Emitter 관리 (sessionId -> SseEmitter)
    private final Map<String, SseEmitter> activeEmitters = new ConcurrentHashMap<>();

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String channel = new String(message.getChannel());
            String body = new String(message.getBody());
            
            log.debug("Redis Pub/Sub 메시지 수신: channel={}, body={}", channel, body);
            
            // 채널 형식: "progress:session:{sessionId}"
            if (channel.startsWith("progress:session:")) {
                String sessionId = channel.substring("progress:session:".length());
                SseEmitter emitter = activeEmitters.get(sessionId);
                
                if (emitter != null) {
                    try {
                        emitter.send(SseEmitter.event()
                            .name("progress")
                            .data(body));
                        log.debug("SSE 전송 성공: sessionId={}", sessionId);
                    } catch (IOException e) {
                        log.error("SSE 전송 실패: sessionId={}", sessionId, e);
                        // 연결이 끊어진 경우 제거
                        activeEmitters.remove(sessionId);
                    }
                } else {
                    log.debug("SSE Emitter 없음: sessionId={}", sessionId);
                }
            }
        } catch (Exception e) {
            log.error("Redis Pub/Sub 메시지 처리 중 오류", e);
        }
    }

    /**
     * SSE Emitter 등록
     * 
     * @param sessionId 세션 ID
     * @param emitter SSE Emitter
     */
    public void registerEmitter(String sessionId, SseEmitter emitter) {
        activeEmitters.put(sessionId, emitter);
        
        // 완료 시 제거
        emitter.onCompletion(() -> {
            activeEmitters.remove(sessionId);
            log.debug("SSE Emitter 제거 (완료): sessionId={}", sessionId);
        });
        
        // 타임아웃 시 제거
        emitter.onTimeout(() -> {
            activeEmitters.remove(sessionId);
            log.debug("SSE Emitter 제거 (타임아웃): sessionId={}", sessionId);
        });
        
        // 에러 시 제거
        emitter.onError((throwable) -> {
            activeEmitters.remove(sessionId);
            log.error("SSE Emitter 제거 (에러): sessionId={}", sessionId, throwable);
        });
        
        log.debug("SSE Emitter 등록: sessionId={}", sessionId);
    }

    /**
     * 활성 Emitter 수 조회
     */
    public int getActiveEmitterCount() {
        return activeEmitters.size();
    }
}
