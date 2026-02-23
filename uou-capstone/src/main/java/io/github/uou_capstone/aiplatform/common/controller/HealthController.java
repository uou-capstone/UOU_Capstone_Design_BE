package io.github.uou_capstone.aiplatform.common.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Health Check Controller
 * 
 * 서버 및 외부 서비스 상태 확인
 * - 서버 상태
 * - Redis 연결 상태
 * - AI 서비스 연결 상태 (선택적)
 */
@Slf4j
@Tag(name = "Health Check", description = "서버 상태 확인 API")
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private final StringRedisTemplate redisTemplate;

    @Operation(summary = "서버 상태 확인", description = "서버 및 Redis 연결 상태를 확인합니다.")
    @GetMapping
    public ResponseEntity<Map<String, Object>> checkHealth() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "ok");
        health.put("timestamp", LocalDateTime.now());
        
        // Redis 상태 확인
        try {
            String testKey = "health_check_" + System.currentTimeMillis();
            redisTemplate.opsForValue().set(testKey, "test", java.time.Duration.ofSeconds(1));
            redisTemplate.delete(testKey);
            health.put("redis", Map.of(
                "status", "connected",
                "message", "Redis 연결 정상"
            ));
        } catch (Exception e) {
            log.warn("Redis health check failed", e);
            health.put("redis", Map.of(
                "status", "disconnected",
                "message", "Redis 연결 실패: " + e.getMessage()
            ));
        }
        
        return ResponseEntity.ok(health);
    }
}

