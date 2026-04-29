package io.github.uou_capstone.aiplatform.security.oauth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * OAuth one-time exchange code 저장소.
 * 키: {@code sb:oauth:exchange:{code}} → userId 문자열, TTL 60s, 1회 사용 (atomic getAndDelete).
 *
 * 콜백에서 발급(issue)하고, FE 가 POST /api/auth/oauth/exchange 에서 소비(consume)한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuthExchangeStore {

    private static final String PREFIX = "sb:oauth:exchange:";
    private static final Duration TTL = Duration.ofSeconds(60);

    private final RedissonClient redissonClient;

    /**
     * userId 에 매핑되는 새 code 를 만들어 저장한다.
     * @return 발급된 code (UUID)
     */
    public String issue(Long userId) {
        String code = UUID.randomUUID().toString();
        String key = PREFIX + code;
        RBucket<String> bucket = redissonClient.getBucket(key);
        bucket.set(String.valueOf(userId), TTL);
        return code;
    }

    /**
     * code 로 저장된 userId 를 atomic 하게 가져오며 즉시 삭제한다.
     * 미존재/만료/이미 사용됨 → empty.
     */
    public Optional<Long> consume(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(PREFIX + code);
            String value = bucket.getAndDelete();
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(Long.parseLong(value));
        } catch (NumberFormatException e) {
            log.error("Stored OAuth exchange value is not a long: code prefix={}",
                    code.substring(0, Math.min(8, code.length())), e);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to consume OAuth exchange code", e);
            return Optional.empty();
        }
    }
}
