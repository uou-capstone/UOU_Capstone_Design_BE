package io.github.uou_capstone.aiplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Redis 캐싱 서비스
 * 
 * 주요 기능:
 * 1. Profile 캐싱: 시험 생성 시 사용되는 Profile 캐싱
 * 2. 세션 상태 캐싱: 생성 세션의 상태 정보 캐싱
 * 3. 캐시 키 생성: MD5 해시 기반 캐시 키 생성
 * 
 * 캐시 키 명명 규칙:
 * - sb:cache:profile:{contentHash} - Profile 캐시
 * - sb:cache:session:{sessionId} - 세션 상태 캐시
 * - sb:cache:exam:{examSessionId} - 시험 세션 캐시
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // ========== 캐시 메트릭 ==========
    private final CacheMetrics profileMetrics = CacheMetrics.builder().build();
    private final CacheMetrics sessionMetrics = CacheMetrics.builder().build();
    private final CacheMetrics examSessionMetrics = CacheMetrics.builder().build();

    // ========== 캐시 키 접두사 ==========
    private static final String PROFILE_CACHE_PREFIX = "sb:cache:profile:";
    private static final String SESSION_CACHE_PREFIX = "sb:cache:session:";
    private static final String EXAM_SESSION_CACHE_PREFIX = "sb:cache:exam:";

    // ========== TTL (Time To Live) 설정 ==========
    private static final long PROFILE_TTL = 86400; // 24시간 (초)
    private static final long SESSION_TTL = 3600; // 1시간 (초)
    private static final long EXAM_SESSION_TTL = 1800; // 30분 (초)

    /**
     * Profile 캐싱
     * 
     * @param contentHash 강의 내용의 MD5 해시
     * @return 캐시된 Profile JSON (Optional)
     */
    public Optional<String> getProfile(String contentHash) {
        try {
            String key = PROFILE_CACHE_PREFIX + contentHash;
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                log.debug("Profile cache hit: {}", contentHash);
                profileMetrics.incrementHits();
                return Optional.of(cached);
            }
            log.debug("Profile cache miss: {}", contentHash);
            profileMetrics.incrementMisses();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Redis connection failed while getting profile cache, falling back to direct generation", e);
            profileMetrics.incrementMisses();
            return Optional.empty(); // Fallback: 직접 생성
        }
    }

    /**
     * Profile 캐싱 저장
     * 
     * @param contentHash 강의 내용의 MD5 해시
     * @param profileJson Profile JSON 문자열
     */
    public void cacheProfile(String contentHash, String profileJson) {
        try {
            String key = PROFILE_CACHE_PREFIX + contentHash;
            redisTemplate.opsForValue().set(key, profileJson, PROFILE_TTL, TimeUnit.SECONDS);
            log.debug("Profile cached: {}", contentHash);
        } catch (Exception e) {
            log.warn("Redis connection failed while caching profile, skipping cache", e);
            // Fallback: 캐시 실패해도 계속 진행
        }
    }

    /**
     * 세션 상태 캐싱
     * 
     * @param sessionId 세션 ID
     * @return 캐시된 세션 상태 JSON (Optional)
     */
    public Optional<String> getSessionStatus(Long sessionId) {
        try {
            String key = SESSION_CACHE_PREFIX + sessionId;
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                log.debug("Session cache hit: {}", sessionId);
                sessionMetrics.incrementHits();
                return Optional.of(cached);
            }
            log.debug("Session cache miss: {}", sessionId);
            sessionMetrics.incrementMisses();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Redis connection failed while getting session cache", e);
            sessionMetrics.incrementMisses();
            return Optional.empty();
        }
    }

    /**
     * 세션 상태 캐싱 저장
     * 
     * @param sessionId 세션 ID
     * @param statusJson 세션 상태 JSON 문자열
     */
    public void cacheSessionStatus(Long sessionId, String statusJson) {
        try {
            String key = SESSION_CACHE_PREFIX + sessionId;
            redisTemplate.opsForValue().set(key, statusJson, SESSION_TTL, TimeUnit.SECONDS);
            log.debug("Session status cached: {}", sessionId);
        } catch (Exception e) {
            log.warn("Redis connection failed while caching session status", e);
        }
    }

    /**
     * 시험 세션 캐싱
     * 
     * @param examSessionId 시험 세션 ID
     * @return 캐시된 시험 세션 JSON (Optional)
     */
    public Optional<String> getExamSession(Long examSessionId) {
        try {
            String key = EXAM_SESSION_CACHE_PREFIX + examSessionId;
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                log.debug("Exam session cache hit: {}", examSessionId);
                examSessionMetrics.incrementHits();
                return Optional.of(cached);
            }
            log.debug("Exam session cache miss: {}", examSessionId);
            examSessionMetrics.incrementMisses();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Redis connection failed while getting exam session cache", e);
            examSessionMetrics.incrementMisses();
            return Optional.empty();
        }
    }

    /**
     * 시험 세션 캐싱 저장
     * 
     * @param examSessionId 시험 세션 ID
     * @param examSessionJson 시험 세션 JSON 문자열
     */
    public void cacheExamSession(Long examSessionId, String examSessionJson) {
        try {
            String key = EXAM_SESSION_CACHE_PREFIX + examSessionId;
            redisTemplate.opsForValue().set(key, examSessionJson, EXAM_SESSION_TTL, TimeUnit.SECONDS);
            log.debug("Exam session cached: {}", examSessionId);
        } catch (Exception e) {
            log.warn("Redis connection failed while caching exam session", e);
        }
    }

    /**
     * 캐시 삭제
     * 
     * @param key 캐시 키
     */
    public void deleteCache(String key) {
        try {
            redisTemplate.delete(key);
            log.debug("Cache deleted: {}", key);
        } catch (Exception e) {
            log.warn("Redis connection failed while deleting cache", e);
        }
    }

    /**
     * Profile 캐시 삭제
     * 
     * @param contentHash 강의 내용의 MD5 해시
     */
    public void deleteProfileCache(String contentHash) {
        deleteCache(PROFILE_CACHE_PREFIX + contentHash);
    }

    /**
     * 세션 상태 캐시 삭제
     * 
     * @param sessionId 세션 ID
     */
    public void deleteSessionCache(Long sessionId) {
        deleteCache(SESSION_CACHE_PREFIX + sessionId);
    }

    /**
     * 시험 세션 캐시 삭제
     * 
     * @param examSessionId 시험 세션 ID
     */
    public void deleteExamSessionCache(Long examSessionId) {
        deleteCache(EXAM_SESSION_CACHE_PREFIX + examSessionId);
    }

    /**
     * 캐시 메트릭 조회
     */
    public CacheMetrics.CacheMetricsInfo getProfileMetrics() {
        return profileMetrics.getInfo();
    }

    public CacheMetrics.CacheMetricsInfo getSessionMetrics() {
        return sessionMetrics.getInfo();
    }

    public CacheMetrics.CacheMetricsInfo getExamSessionMetrics() {
        return examSessionMetrics.getInfo();
    }

    // ========== 범용 캐시 메서드 (Material Generation용) ==========
    
    /**
     * 범용 캐시 저장
     * 
     * @param key 캐시 키
     * @param value 저장할 객체
     * @param ttlSeconds TTL (초)
     */
    public <T> void set(String key, T value, long ttlSeconds) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, ttlSeconds, TimeUnit.SECONDS);
            log.debug("Cache set: key={}, ttl={}s", key, ttlSeconds);
        } catch (Exception e) {
            log.warn("Redis connection failed while setting cache: key={}", key, e);
        }
    }

    /**
     * 범용 캐시 조회
     * 
     * @param key 캐시 키
     * @param clazz 반환 타입
     * @return 캐시된 객체 (Optional)
     */
    public <T> Optional<T> get(String key, Class<T> clazz) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                T value = objectMapper.readValue(json, clazz);
                log.debug("Cache hit: key={}", key);
                return Optional.of(value);
            }
            log.debug("Cache miss: key={}", key);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Redis connection failed while getting cache: key={}", key, e);
            return Optional.empty();
        }
    }

    /**
     * 범용 캐시 삭제
     * 
     * @param key 캐시 키
     */
    public void delete(String key) {
        try {
            redisTemplate.delete(key);
            log.debug("Cache deleted: key={}", key);
        } catch (Exception e) {
            log.warn("Redis connection failed while deleting cache: key={}", key, e);
        }
    }
}
