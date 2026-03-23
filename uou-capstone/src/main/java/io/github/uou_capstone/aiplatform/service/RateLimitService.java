package io.github.uou_capstone.aiplatform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Rate Limiting 서비스 (Redis 기반)
 * 
 * API 호출 제한을 관리합니다.
 * - 사용자별 호출 제한
 * - API 엔드포인트별 호출 제한
 * - 시간 윈도우 기반 제한 (예: 분당 10회)
 * 
 * Redis를 사용하여 분산 환경에서도 동작합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final StringRedisTemplate redisTemplate;

    // ========== Rate Limit 통계 ==========
    private final java.util.concurrent.atomic.AtomicLong totalRequests = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong allowedRequests = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong blockedRequests = new java.util.concurrent.atomic.AtomicLong(0);

    // ========== Rate Limit 설정 ==========
    private static final String RATE_LIMIT_PREFIX = "sb:rate_limit:";
    private static final int DEFAULT_MAX_REQUESTS_PER_MINUTE = 100;  // 기본: 분당 100회 (개발/테스트 환경)
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(1);  // 1분 윈도우

    /**
     * Rate Limit 확인 (사용자별)
     * 
     * @param userId 사용자 ID
     * @return 허용 여부
     */
    public boolean isAllowed(Long userId) {
        return isAllowedInternal("user:" + userId, DEFAULT_MAX_REQUESTS_PER_MINUTE);
    }

    /**
     * Rate Limit 확인 (사용자별, 커스텀 제한)
     * 
     * @param userId 사용자 ID
     * @param maxRequests 최대 요청 수
     * @return 허용 여부
     */
    public boolean isAllowed(Long userId, int maxRequests) {
        return isAllowedInternal("user:" + userId, maxRequests);
    }

    /**
     * Rate Limit 확인 (API 엔드포인트별)
     * 
     * @param endpoint API 엔드포인트
     * @param maxRequests 최대 요청 수
     * @return 허용 여부
     */
    public boolean isAllowedForEndpoint(String endpoint, int maxRequests) {
        return isAllowedInternal("endpoint:" + endpoint, maxRequests);
    }

    /**
     * Rate Limit 확인 (내부 메서드)
     * 
     * @param key Redis 키 (예: "user:123", "endpoint:/api/materials/generation")
     * @param maxRequests 최대 요청 수
     * @return 허용 여부
     */
    private boolean isAllowedInternal(String key, int maxRequests) {
        totalRequests.incrementAndGet();
        
        try {
            String redisKey = RATE_LIMIT_PREFIX + key + ":" + getCurrentMinute();
            
            // 현재 요청 수 조회
            String countStr = redisTemplate.opsForValue().get(redisKey);
            int currentCount = countStr != null ? Integer.parseInt(countStr) : 0;
            
            // 제한 초과 확인
            if (currentCount >= maxRequests) {
                blockedRequests.incrementAndGet();
                log.warn("Rate limit exceeded: key={}, currentCount={}, maxRequests={}", key, currentCount, maxRequests);
                return false;
            }
            
            // 요청 수 증가
            redisTemplate.opsForValue().increment(redisKey);
            redisTemplate.expire(redisKey, RATE_LIMIT_WINDOW);
            
            allowedRequests.incrementAndGet();
            return true;
        } catch (Exception e) {
            allowedRequests.incrementAndGet();  // Redis 오류 시 허용 (Fallback)
            log.error("Rate limit check failed: key={}", key, e);
            return true;
        }
    }

    /**
     * 현재 분(분 단위) 반환
     * 예: "2024-01-01T12:34" -> "2024-01-01T12:34"
     */
    private String getCurrentMinute() {
        LocalDateTime now = LocalDateTime.now();
        return now.toString().substring(0, 16);  // "yyyy-MM-ddTHH:mm"
    }

    /**
     * Rate Limit 정보 조회
     * 
     * @param userId 사용자 ID
     * @return 현재 요청 수와 최대 요청 수
     */
    public RateLimitInfo getRateLimitInfo(Long userId) {
        try {
            String redisKey = RATE_LIMIT_PREFIX + "user:" + userId + ":" + getCurrentMinute();
            String countStr = redisTemplate.opsForValue().get(redisKey);
            int currentCount = countStr != null ? Integer.parseInt(countStr) : 0;
            
            return RateLimitInfo.builder()
                    .currentCount(currentCount)
                    .maxRequests(DEFAULT_MAX_REQUESTS_PER_MINUTE)
                    .remainingRequests(Math.max(0, DEFAULT_MAX_REQUESTS_PER_MINUTE - currentCount))
                    .windowSeconds((int) RATE_LIMIT_WINDOW.getSeconds())
                    .build();
        } catch (Exception e) {
            log.error("Rate limit info retrieval failed: userId={}", userId, e);
            return RateLimitInfo.builder()
                    .currentCount(0)
                    .maxRequests(DEFAULT_MAX_REQUESTS_PER_MINUTE)
                    .remainingRequests(DEFAULT_MAX_REQUESTS_PER_MINUTE)
                    .windowSeconds((int) RATE_LIMIT_WINDOW.getSeconds())
                    .build();
        }
    }

    /**
     * Rate Limit 통계 조회
     */
    public RateLimitStats getStats() {
        long total = totalRequests.get();
        long allowed = allowedRequests.get();
        long blocked = blockedRequests.get();
        
        return RateLimitStats.builder()
                .totalRequests(total)
                .allowedRequests(allowed)
                .blockedRequests(blocked)
                .blockRate(total > 0 ? (double) blocked / total : 0.0)
                .build();
    }

    /**
     * Rate Limit 정보 DTO
     */
    @lombok.Data
    @lombok.Builder
    public static class RateLimitInfo {
        private int currentCount;
        private int maxRequests;
        private int remainingRequests;
        private int windowSeconds;
    }

    /**
     * Rate Limit 통계 DTO
     */
    @lombok.Data
    @lombok.Builder
    public static class RateLimitStats {
        private long totalRequests;
        private long allowedRequests;
        private long blockedRequests;
        private double blockRate;  // 0.0 ~ 1.0
    }
}
