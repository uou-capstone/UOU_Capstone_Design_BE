package io.github.uou_capstone.aiplatform.service;

import lombok.Builder;
import lombok.Data;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 캐시 메트릭
 * 
 * 캐시 Hit/Miss 통계를 관리합니다.
 */
@Data
@Builder
public class CacheMetrics {
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);

    /**
     * Hit 증가
     */
    public void incrementHits() {
        hits.incrementAndGet();
    }

    /**
     * Miss 증가
     */
    public void incrementMisses() {
        misses.incrementAndGet();
    }

    /**
     * Hit 비율 계산 (0.0 ~ 1.0)
     */
    public double getHitRate() {
        long total = hits.get() + misses.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) hits.get() / total;
    }

    /**
     * 총 요청 수
     */
    public long getTotalRequests() {
        return hits.get() + misses.get();
    }

    /**
     * 통계 초기화
     */
    public void reset() {
        hits.set(0);
        misses.set(0);
    }

    /**
     * 통계 정보 반환
     */
    public CacheMetricsInfo getInfo() {
        return CacheMetricsInfo.builder()
                .hits(hits.get())
                .misses(misses.get())
                .totalRequests(getTotalRequests())
                .hitRate(getHitRate())
                .build();
    }

    @Data
    @Builder
    public static class CacheMetricsInfo {
        private long hits;
        private long misses;
        private long totalRequests;
        private double hitRate;  // 0.0 ~ 1.0
    }
}
