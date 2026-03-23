package io.github.uou_capstone.aiplatform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * JWT 토큰 블랙리스트 관리 서비스
 * 로그아웃한 토큰을 Redis에 저장하여 재사용을 방지합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private final StringRedisTemplate redisTemplate;
    private static final String BLACKLIST_PREFIX = "sb:blacklist:token:";

    /**
     * 토큰을 블랙리스트에 추가
     * 
     * @param token JWT 토큰
     * @param expirationTimeMillis 토큰 만료 시간까지 남은 시간 (밀리초)
     */
    public void addToBlacklist(String token, long expirationTimeMillis) {
        try {
            String key = BLACKLIST_PREFIX + token;
            // 토큰 만료 시간까지 Redis에 저장
            redisTemplate.opsForValue().set(key, "blacklisted", expirationTimeMillis, TimeUnit.MILLISECONDS);
            log.debug("Token added to blacklist: {}", token.substring(0, Math.min(20, token.length())) + "...");
        } catch (Exception e) {
            log.error("Failed to add token to blacklist", e);
            // Redis 오류 시에도 계속 진행 (Fallback)
        }
    }

    /**
     * 토큰이 블랙리스트에 있는지 확인
     * 
     * @param token JWT 토큰
     * @return 블랙리스트에 있으면 true
     */
    public boolean isBlacklisted(String token) {
        try {
            String key = BLACKLIST_PREFIX + token;
            String value = redisTemplate.opsForValue().get(key);
            return value != null;
        } catch (Exception e) {
            log.error("Failed to check token blacklist", e);
            // Redis 오류 시 false 반환 (Fallback)
            return false;
        }
    }

    /**
     * 토큰을 블랙리스트에서 제거 (일반적으로 사용하지 않음)
     * 
     * @param token JWT 토큰
     */
    public void removeFromBlacklist(String token) {
        try {
            String key = BLACKLIST_PREFIX + token;
            redisTemplate.delete(key);
            log.debug("Token removed from blacklist: {}", token.substring(0, Math.min(20, token.length())) + "...");
        } catch (Exception e) {
            log.error("Failed to remove token from blacklist", e);
        }
    }
}
