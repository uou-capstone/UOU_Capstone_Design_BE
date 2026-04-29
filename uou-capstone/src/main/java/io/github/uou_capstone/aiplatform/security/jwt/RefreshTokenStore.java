package io.github.uou_capstone.aiplatform.security.jwt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;


/**
 * Refresh 토큰 jti 화이트리스트.
 * 키: {@code sb:refresh:{userId}:{jti}} → "1", TTL = refresh 토큰 잔여수명.
 *
 * - register: 로그인/회전/OAuth exchange 시점에 새 jti 등록
 * - isValid: 회전 시점에 기존 jti 가 살아 있는지 확인 (replay 감지)
 * - revoke: 회전 성공 시 기존 jti 무효화
 * - revokeAllForUser: 재사용 감지 시 또는 로그아웃 시 모든 디바이스 무효화
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

    private static final String PREFIX = "sb:refresh:";

    private final StringRedisTemplate redisTemplate;

    public void register(Long userId, String jti, long ttlMillis) {
        try {
            redisTemplate.opsForValue().set(buildKey(userId, jti), "1", ttlMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("Failed to register refresh jti: userId={}", userId, e);
        }
    }

    public boolean isValid(Long userId, String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        try {
            String value = redisTemplate.opsForValue().get(buildKey(userId, jti));
            return value != null;
        } catch (Exception e) {
            log.error("Failed to check refresh jti: userId={}", userId, e);
            return false;
        }
    }

    public void revoke(Long userId, String jti) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(buildKey(userId, jti));
        } catch (Exception e) {
            log.error("Failed to revoke refresh jti: userId={}", userId, e);
        }
    }

    public void revokeAllForUser(Long userId) {
        Set<String> keys = new HashSet<>();
        ScanOptions options = ScanOptions.scanOptions().match(PREFIX + userId + ":*").count(100).build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        } catch (Exception e) {
            log.error("Failed to scan refresh jtis: userId={}", userId, e);
        }
        if (!keys.isEmpty()) {
            try {
                redisTemplate.delete(keys);
            } catch (Exception e) {
                log.error("Failed to delete refresh jtis: userId={}, count={}", userId, keys.size(), e);
            }
        }
    }

    private String buildKey(Long userId, String jti) {
        return PREFIX + userId + ":" + jti;
    }
}
